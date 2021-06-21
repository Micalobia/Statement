package virtuoel.statement.mixin.sync;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import virtuoel.statement.Statement;

@Mixin(value = ChunkDeltaUpdateS2CPacket.class, priority = 1050)
public class ChunkDeltaUpdateS2CPacketMixin
{
	@Redirect(method = "write(Lnet/minecraft/network/PacketByteBuf;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;getRawIdFromState(Lnet/minecraft/block/BlockState;)I"))
	private int writeGetRawIdFromStateProxy(BlockState state)
	{
		return Statement.getSyncedBlockStateId(state).orElseGet(() -> Block.getRawIdFromState(state));
	}
}
