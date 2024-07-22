package com.codinglitch.simpleradio.radio;

import com.codinglitch.simpleradio.CompatCore;
import com.codinglitch.simpleradio.SimpleRadioLibrary;
import com.codinglitch.simpleradio.core.central.Frequency;
import com.codinglitch.simpleradio.core.central.Receiving;
import com.codinglitch.simpleradio.core.central.WorldlyPosition;
import com.codinglitch.simpleradio.platform.Services;
import com.codinglitch.simpleradio.radio.effects.AudioEffect;
import com.codinglitch.simpleradio.radio.effects.BaseAudioEffect;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Supplier;

/**
 * This class currently serves as both the receiver as well as the sound-playing class.
 */
public class RadioChannel implements Supplier<short[]> {
    public UUID owner;
    public WorldlyPosition location;

    public float range;
    public String category;

    public AudioChannel audioChannel;
    public AudioPlayer audioPlayer;
    private final Map<UUID, List<short[]>> packetBuffer;
    private final Map<UUID, OpusDecoder> decoders;
    private final AudioEffect effect;
    private final Frequency frequency;

    public boolean isValid = true;

    public RadioChannel(Player owner, Frequency frequency) {
        this(owner.getUUID(), frequency);
    }
    public RadioChannel(UUID owner, Frequency frequency) {
        this.owner = owner;
        this.frequency = frequency;

        packetBuffer = new HashMap<>();
        decoders = new HashMap<>();
        effect = new BaseAudioEffect();
    }

    @Override
    public short[] get() {
        short[] audio = generatePacket();
        if (audio == null) {
            if (audioPlayer != null)
                audioPlayer.stopPlaying();

            audioPlayer = null;
            return null;
        }
        return audio;
    }

    public short[] generatePacket() {
        List<short[]> packetsToCombine = new ArrayList<>();
        for (Map.Entry<UUID, List<short[]>> packets : packetBuffer.entrySet()) {
            if (packets.getValue().isEmpty()) continue;
            short[] audio = packets.getValue().remove(0);
            packetsToCombine.add(audio);
        }
        packetBuffer.values().removeIf(List::isEmpty);

        if (packetsToCombine.isEmpty()) return null;

        short[] combinedAudio = CommonRadioPlugin.combineAudio(packetsToCombine);

        return effect.apply(combinedAudio);
    }

    public void updateLocation(WorldlyPosition location) {
        if (this.audioChannel instanceof LocationalAudioChannel locationalAudioChannel) {
            locationalAudioChannel.updateLocation(CommonRadioPlugin.serverApi.createPosition(location.x, location.y, location.z));
        }
    }

    public void serverTick(int tickCount) {
        if (location != null) {
            Services.COMPAT.modifyPosition(location);
            this.updateLocation(location);
        }
    }

    public void transmit(RadioSource source, Frequency frequency) {
        // Severity calculation
        ServerLevel level = null;
        Vector3f position = null;
        if (location != null) {
            level = (ServerLevel) location.level;
            position = location.position();
        } else {
            VoicechatConnection connection = CommonRadioPlugin.serverApi.getConnectionOf(owner);
            if (connection != null) {
                ServerPlayer player = (ServerPlayer) connection.getPlayer().getPlayer();
                if (player != null) {
                    level = player.serverLevel();
                    position = player.position().toVector3f();
                }
            }
        }
        if (level == null || position == null) return;

        if (!SimpleRadioLibrary.SERVER_CONFIG.frequency.crossDimensional && level != source.location.level) return;

        this.effect.severity = source.computeSeverity(WorldlyPosition.of(position, level), frequency);
        this.effect.volume = source.volume;
        if (this.effect.severity >= 100) return;

        // Packet buffer
        List<short[]> microphonePackets = packetBuffer.computeIfAbsent(source.owner, k -> new ArrayList<>());
        if (microphonePackets.isEmpty()) {
            for (int i = 0; i < SimpleRadioLibrary.SERVER_CONFIG.frequency.packetBuffer; i++) {
                microphonePackets.add(null);
            }
        }

        // Decoding
        byte[] data = source.data;

        OpusDecoder decoder = getDecoder(source.owner);
        if (data == null || data.length == 0) {
            decoder.resetState();
            return;
        }
        short[] decoded = decoder.decode(data);
        microphonePackets.add(decoded);

        // Loader-specific compat
        Services.COMPAT.onData(this, source, decoded);

        // Common compat
        CompatCore.onData(this, source, decoded);

        if (this.audioPlayer == null)
            getAudioPlayer().startPlaying();
    }

    public boolean validate() {
        VoicechatConnection connection = CommonRadioPlugin.serverApi.getConnectionOf(owner);
        if (connection == null) {
            if (location == null || !Receiving.validateReceiver(location, frequency)) {
                invalidate();
                return false;
            }
        } else {
            if (!Receiving.validateReceiver(connection, frequency)) {
                invalidate();
                return false;
            }
        }

        return true;
    }
    public void invalidate() {
        if (this.audioPlayer != null)
            this.audioPlayer.stopPlaying();

        this.isValid = false;
    }

    public OpusDecoder getDecoder(UUID sender) {
        return decoders.computeIfAbsent(sender, uuid -> CommonRadioPlugin.serverApi.createDecoder());
    }

    private AudioPlayer getAudioPlayer() {
        if (this.audioPlayer == null) {
            VoicechatConnection connection = CommonRadioPlugin.serverApi.getConnectionOf(owner);
            if (connection == null) {
                LocationalAudioChannel locationalChannel = CommonRadioPlugin.serverApi.createLocationalAudioChannel(this.owner,
                        CommonRadioPlugin.serverApi.fromServerLevel(location.level),
                        CommonRadioPlugin.serverApi.createPosition(location.x + 0.5, location.y + 0.5, location.z + 0.5)
                );
                locationalChannel.setDistance(this.range);
                locationalChannel.setCategory(this.category);

                this.audioChannel = locationalChannel;
            } else {
                this.audioChannel = CommonRadioPlugin.serverApi.createEntityAudioChannel(this.owner, connection.getPlayer());
                audioChannel.setCategory(this.category);
            }

            this.audioPlayer = CommonRadioPlugin.serverApi.createAudioPlayer(audioChannel, CommonRadioPlugin.serverApi.createEncoder(), this);
        }
        return this.audioPlayer;
    }
}
