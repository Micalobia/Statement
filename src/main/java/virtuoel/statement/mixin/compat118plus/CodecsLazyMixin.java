package virtuoel.statement.mixin.compat118plus;

import com.mojang.serialization.Codec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

@Mixin(targets = {"net/minecraft/util/dynamic/Codecs$Lazy"})
public class CodecsLazyMixin<A> {
	@Shadow(remap = false)
	@Final
	private Supplier<Codec<A>> delegate;

	@Inject(method = "delegate", at = @At("HEAD"), cancellable = true)
	public void fixFieldError(CallbackInfoReturnable<Supplier<Codec<A>>> cir) {
		cir.setReturnValue(this.delegate);
	}
}
