package com.codinglitch.simpleradio.core.registry.items;

import com.codinglitch.simpleradio.CommonSimpleRadio;
import com.codinglitch.simpleradio.SimpleRadioLibrary;
import com.codinglitch.simpleradio.core.central.Receiving;
import com.codinglitch.simpleradio.core.central.Transmitting;
import com.codinglitch.simpleradio.core.networking.packets.ClientboundRadioPacket;
import com.codinglitch.simpleradio.core.registry.SimpleRadioSounds;
import com.codinglitch.simpleradio.platform.Services;
import com.codinglitch.simpleradio.core.central.Frequency;
import com.codinglitch.simpleradio.radio.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class TransceiverItem extends Item implements Receiving, Transmitting {
    public TransceiverItem(Properties settings) {
        super(settings);
    }

    private void transmit(ServerPlayer player, boolean started) {
        Services.NETWORKING.sendToPlayer(player, new ClientboundRadioPacket(started, player.getUUID(), this.getClass().getName()));
    }

    private void startTransceiving(Level level, ItemStack stack, String frequencyName, String modulation, UUID owner) {
        RadioChannel channel = startReceiving(frequencyName, Frequency.modulationOf(modulation), owner);

        Player player = level.getPlayerByUUID(owner);
        RadioListener listener = startListening(player);

        if (this.getClass() == TransceiverItem.class) {
            channel.range = SimpleRadioLibrary.SERVER_CONFIG.transceiver.speakingRange;
            listener.range = SimpleRadioLibrary.SERVER_CONFIG.transceiver.listeningRange;
            channel.category = CommonRadioPlugin.TRANSCEIVERS_CATEGORY;
        } else if (this.getClass() == WalkieTalkieItem.class) {
            channel.range = SimpleRadioLibrary.SERVER_CONFIG.walkie_talkie.speakingRange;
            listener.range = SimpleRadioLibrary.SERVER_CONFIG.walkie_talkie.listeningRange;
            channel.category = CommonRadioPlugin.WALKIES_CATEGORY;
        }

        listener.acceptor(source -> {
            ItemStack using = player.getUseItem();
            CompoundTag usingTag = using.getOrCreateTag();

            if (!usingTag.contains("frequency") || !usingTag.contains("modulation")) return;
            if (!usingTag.getString("frequency").equals(frequencyName) || !usingTag.getString("modulation").equals(modulation)) return;

            if (this.getClass() == TransceiverItem.class) source.type = RadioSource.Type.TRANSCEIVER;
            else if (this.getClass() == WalkieTalkieItem.class) source.type = RadioSource.Type.WALKIE_TALKIE;

            Frequency frequency = getFrequency(stack);
            if (frequency != null) RadioManager.transmit(source, frequency);
        });
    }
    private void stopTransceiving(Level level, String frequencyName, String modulation, UUID owner) {
        stopReceiving(frequencyName, Frequency.modulationOf(modulation), owner);

        Entity entity = level.getPlayerByUUID(owner);
        stopListening(entity);
    }

    public int getCooldown() {
        return 20;
    }

    @Override
    public void verifyTagAfterLoad(CompoundTag tag) {
        super.verifyTagAfterLoad(tag);

        if (tag.contains("user"))
            tag.remove("user");
    }

    @Override
    public void onDestroyed(ItemEntity itemEntity) {
        super.onDestroyed(itemEntity);
        CompoundTag tag = itemEntity.getItem().getOrCreateTag();
        if (tag.contains("frequency") && tag.contains("modulation") && tag.contains("user")) {
            stopTransceiving(itemEntity.level(), tag.getString("frequency"), tag.getString("modulation"), tag.getUUID("user"));
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean b) {
        super.inventoryTick(stack, level, entity, slot, b);

        CompoundTag tag = stack.getOrCreateTag();

        String frequency = tag.getString("frequency");
        String modulation = tag.getString("modulation");
        tick(stack, level);
        if (frequency.isEmpty() || modulation.isEmpty()) return;

        if (!Frequency.check(frequency)) {
            CommonSimpleRadio.info("Invalid frequency {}, replacing with default", frequency);
            frequency = this.getDefaultFrequency();
            tag.putString("frequency", frequency);
        }

        UUID uuid = entity.getUUID();
        if (tag.contains("user")) {
            UUID currentUUID = tag.getUUID("user");
            if (currentUUID.equals(uuid)) {
                if (validate(frequency, Frequency.modulationOf(modulation), currentUUID)) return;
            } else {
                if (!level.isClientSide) {
                    stopTransceiving(level, frequency, modulation, currentUUID);
                }
            }
        }

        frequency = tag.getString("frequency");
        modulation = tag.getString("modulation");
        if (!level.isClientSide) {
            startTransceiving(level, stack, frequency, modulation, uuid);
        }

        tag.putUUID("user", uuid);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components, TooltipFlag tooltip) {
        appendTooltip(stack, components);
        super.appendHoverText(stack, level, components, tooltip);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(
                player, player.blockPosition(),
                SimpleRadioSounds.RADIO_OPEN,
                SoundSource.PLAYERS,
                1f,1f
        );
        player.startUsingItem(hand);

        // Send started using packet
        if (!level.isClientSide) {
            transmit((ServerPlayer) player, true);
        }

        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.TOOT_HORN;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingUseTicks) {
        if (user instanceof Player player) {
            level.playSound(
                    player, user.blockPosition(),
                    SimpleRadioSounds.RADIO_CLOSE,
                    SoundSource.PLAYERS,
                    1f,1f
            );

            // Send stopped using packet
            if (!level.isClientSide) {
                transmit((ServerPlayer) player, false);
            }

            player.getCooldowns().addCooldown(this, this.getCooldown());
        }

        super.releaseUsing(stack, level, user, remainingUseTicks);
    }
}
