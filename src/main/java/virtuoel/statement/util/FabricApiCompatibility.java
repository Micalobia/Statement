package virtuoel.statement.util;

import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.State;
import net.minecraft.state.property.Property;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.IdList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.BlockView;
import virtuoel.statement.Statement;
import virtuoel.statement.api.StateRefresher;

public class FabricApiCompatibility
{
	public static void setupCommands(final boolean networkingLoaded)
	{
		CommandRegistrationCallback.EVENT.register((commandDispatcher, dedicated) ->
		{
			commandDispatcher.register(
				CommandManager.literal("statement")
				.requires(commandSource ->
				{
					return commandSource.hasPermissionLevel(2);
				})
				.then(
					CommandManager.literal("validate")
					.then(stateValidationArgument("block_state", Statement.BLOCK_STATE_VALIDATION_PACKET, networkingLoaded))
					.then(stateValidationArgument("fluid_state", Statement.FLUID_STATE_VALIDATION_PACKET, networkingLoaded))
				)
				.then(
					CommandManager.literal("get_id")
					.then(idGetterArgument("block_state", Block.STATE_IDS, BlockView::getBlockState, Registry.BLOCK, s -> ((StatementBlockStateExtensions) s).statement_getBlock()))
					.then(idGetterArgument("fluid_state", Fluid.STATE_IDS, BlockView::getFluidState, Registry.FLUID, s -> ((StatementFluidStateExtensions) (Object) s).statement_getFluid()))
				)
			);
		});
	}
	
	@SuppressWarnings("unchecked")
	private static <O, S extends State<O, S>> ArgumentBuilder<ServerCommandSource, ?> idGetterArgument(final String argumentName, IdList<S> idList, final BiFunction<ServerWorld, BlockPos, S> stateFunc, Registry<O> registry, Function<S, O> entryFunction)
	{
		return CommandManager.literal(argumentName)
			.then(
				CommandManager.argument("pos", BlockPosArgumentType.blockPos())
				.executes(context ->
				{
					final BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(context, "pos");
					final S state = stateFunc.apply(context.getSource().getWorld(), pos);
					
					final StringBuilder stringBuilder = new StringBuilder();
					stringBuilder.append(registry.getId(entryFunction.apply(state)));
					
					if (!state.getEntries().isEmpty())
					{
						stringBuilder.append('[');
						stringBuilder.append(state.getEntries().entrySet().stream().map(entry ->
						{
							@SuppressWarnings("rawtypes")
							final Property property = entry.getKey();
							return property.getName() + "=" + property.name(entry.getValue());
						}).collect(Collectors.joining(",")));
						stringBuilder.append(']');
					}
					
					context.getSource().sendFeedback(new LiteralText(String.format("%s (%d) @ %d, %d, %d", stringBuilder.toString(), idList.getRawId(state), pos.getX(), pos.getY(), pos.getZ())), false);
					return 1;
				})
			);
	}
	
	private static ArgumentBuilder<ServerCommandSource, ?> stateValidationArgument(final String argumentName, final Identifier packetId, final boolean networkingLoaded)
	{
		return CommandManager.literal(argumentName)
			.executes(context ->
			{
				return executeValidation(context, networkingLoaded, packetId, context.getSource().getPlayer(), 100, 0);
			})
			.then(
				CommandManager.argument("player", EntityArgumentType.player())
				.executes(context ->
				{
					return executeValidation(context, networkingLoaded, packetId, EntityArgumentType.getPlayer(context, "player"), 100, 0);
				})
				.then(
					CommandManager.argument("rate", IntegerArgumentType.integer(1, Block.STATE_IDS.size()))
					.executes(context ->
					{
						return executeValidation(context, networkingLoaded, packetId, EntityArgumentType.getPlayer(context, "player"), IntegerArgumentType.getInteger(context, "rate"), 0);
					})
					.then(
						CommandManager.argument("start_id", IntegerArgumentType.integer(0, Block.STATE_IDS.size() - 1))
						.executes(context ->
						{
							return executeValidation(context, networkingLoaded, packetId, EntityArgumentType.getPlayer(context, "player"), IntegerArgumentType.getInteger(context, "rate"), IntegerArgumentType.getInteger(context, "start_id"));
						})
					)
				)
			);
	}
	
	private static int executeValidation(final CommandContext<ServerCommandSource> context, final boolean networkingLoaded, final Identifier packetId, final ServerPlayerEntity player, final int rate, final int initialId) throws CommandSyntaxException
	{
		if (networkingLoaded)
		{
			if (ServerPlayNetworking.canSend(player, packetId))
			{
				final PlayerEntity executor = context.getSource().getPlayer();
				final PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer()).writeUuid(executor.getUuid()).writeVarInt(rate);
				
				for (int i = 0; i < rate; i++)
				{
					buffer.writeVarInt(initialId + i);
				}
				
				context.getSource().sendFeedback(new LiteralText("Running state validation..."), false);
				
				ServerPlayNetworking.send(player, packetId, buffer);
				
				return 1;
			}
			else
			{
				context.getSource().sendFeedback(new LiteralText("Error: Target player cannot receive state validation packet."), false);
				return 0;
			}
		}
		else
		{
			context.getSource().sendFeedback(new LiteralText("Fabric Networking not found on server."), false);
			return 0;
		}
	}
	
	public static void setupServerNetworking()
	{
		setupServerStateValidation(Statement.BLOCK_STATE_VALIDATION_PACKET, Block.STATE_IDS, NbtHelper::fromBlockState);
		setupServerStateValidation(Statement.FLUID_STATE_VALIDATION_PACKET, Fluid.STATE_IDS, FabricApiCompatibility::fromFluidState);
	}
	
	public static <S> void setupServerStateValidation(final Identifier packetId, final IdList<S> stateIdList, final Function<S, NbtCompound> stateToNbtFunction)
	{
		ServerPlayNetworking.registerGlobalReceiver(packetId, (server, player, handler, buf, responseSender) ->
		{
			final UUID uuid = buf.readUuid();
			
			final int idQuantity = buf.readVarInt();
			
			if (idQuantity == 0)
			{
				return;
			}
			
			final int[] ids = new int[idQuantity];
			final String[] snbts = new String[idQuantity];
			
			for (int i = 0; i < idQuantity; i++)
			{
				ids[i] = buf.readVarInt();
				snbts[i] = buf.readString(32767);
			}
			
			server.execute(() ->
			{
				final PlayerEntity executor = player.getEntityWorld().getPlayerByUuid(uuid);
				
				if (!executor.isSneaking())
				{
					boolean idsFound = false;
					boolean done = false;
					
					for (int i = 0; i < idQuantity; i++)
					{
						final S state = stateIdList.get(ids[i]);
						
						try
						{
							final NbtCompound sentData = StringNbtReader.parse(snbts[i]);
							final String sentName = sentData.getString("Name");
							
							final StringBuilder sentStringBuilder = new StringBuilder();
							sentStringBuilder.append(sentName);
							
							if (sentData.contains("Properties", 10))
							{
								sentStringBuilder.append('[');
								final NbtCompound properties = sentData.getCompound("Properties");
								sentStringBuilder.append(properties.getKeys().stream().map(key ->
								{
									return key + "=" + properties.getString(key);
								}).collect(Collectors.joining(",")));
								sentStringBuilder.append(']');
							}
							
							if (state != null)
							{
								final NbtCompound ownData = stateToNbtFunction.apply(state);
								
								final int total = stateIdList.size();
								final float percent = ((float) (ids[i] + 1) / total) * 100;
								
								if (sentData.equals(ownData))
								{
									executor.sendMessage(new LiteralText(String.format("ID %d matched (%d/%d: %.2f%%):\n%s", ids[i], ids[i] + 1, total, percent, sentStringBuilder.toString())), false);
								}
								else
								{
									final String ownName = ownData.getString("Name");
									
									final StringBuilder ownStringBuilder = new StringBuilder();
									ownStringBuilder.append(ownName);
									
									if (ownData.contains("Properties", 10))
									{
										ownStringBuilder.append('[');
										final NbtCompound properties = ownData.getCompound("Properties");
										ownStringBuilder.append(properties.getKeys().stream().map(key ->
										{
											return key + "=" + properties.getString(key);
										}).collect(Collectors.joining(",")));
										ownStringBuilder.append(']');
									}
									
									if (sentName.equals(ownName))
									{
										executor.sendMessage(new LiteralText(String.format("ID %d partially matched (%d/%d: %.2f%%):\nServer state:\n%s\nClient state:\n%s", ids[i], ids[i] + 1, total, percent, ownStringBuilder.toString(), sentStringBuilder.toString())), false);
									}
									else
									{
										executor.sendMessage(new LiteralText(String.format("ID %d mismatched (%d/%d: %.2f%%)!\nServer state:\n%s\nClient state:\n%s", ids[i], ids[i] + 1, total, percent, ownStringBuilder.toString(), sentStringBuilder.toString())), false);
									}
								}
								
								idsFound = true;
							}
							else
							{
								executor.sendMessage(new LiteralText(String.format("Received ID %d not found on server.\nClient state:\n%s", ids[i], sentStringBuilder.toString())), false);
							}
						}
						catch (CommandSyntaxException e)
						{
							if (state == null)
							{
								executor.sendMessage(new LiteralText("Done matching after " + ids[i] + " states."), false);
								done = true;
								break;
							}
							executor.sendMessage(new LiteralText("Failed to parse received state from SNBT:\n" + snbts[i]), false);
						}
					}
					
					if (!done && idsFound)
					{
						if (ServerPlayNetworking.canSend(player, packetId))
						{
							final PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer()).writeUuid(uuid).writeVarInt(idQuantity);
							
							for (int i = 0; i < idQuantity; i++)
							{
								buffer.writeVarInt(ids[i] + idQuantity);
							}
							
							ServerPlayNetworking.send(player, packetId, buffer);
						}
						else
						{
							executor.sendMessage(new LiteralText("Error: Target player cannot receive state validation packet."), false);
						}
					}
				}
			});
		});
	}
	
	public static void setupClientNetworking()
	{
		setupClientStateValidation(Statement.BLOCK_STATE_VALIDATION_PACKET, Block.STATE_IDS, NbtHelper::fromBlockState);
		setupClientStateValidation(Statement.FLUID_STATE_VALIDATION_PACKET, Fluid.STATE_IDS, FabricApiCompatibility::fromFluidState);
	}
	
	public static <S> void setupClientStateValidation(final Identifier packetId, final IdList<S> stateIdList, final Function<S, NbtCompound> stateToNbtFunction)
	{
		ClientPlayNetworking.registerGlobalReceiver(packetId, (client, handler, buf, responseSender) ->
		{
			final ClientPlayerEntity player = client.player;
			
			final UUID uuid = buf.readUuid();
			final int idQuantity = buf.readVarInt();
			
			if (idQuantity == 0)
			{
				return;
			}
			
			final int[] ids = new int[idQuantity];
			
			for (int i = 0; i < idQuantity; i++)
			{
				ids[i] = buf.readVarInt();
			}
			
			client.execute(() ->
			{
				if (!player.isSneaking())
				{
					final PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer()).writeUuid(uuid).writeVarInt(idQuantity);
					
					for (int i = 0; i < idQuantity; i++)
					{
						final S state = stateIdList.get(ids[i]);
						final String snbt = state == null ? "No state found on client for ID " + ids[i] : stateToNbtFunction.apply(state).toString();
						
						buffer.writeVarInt(ids[i]).writeString(snbt);
					}
					
					ClientPlayNetworking.send(packetId, buffer);
				}
			});
		});
	}
	
	public static NbtCompound fromFluidState(final FluidState state)
	{
		return fromState(Registry.FLUID, FluidState::getFluid, state);
	}
	
	public static <S extends State<?, S>, E> NbtCompound fromState(final Registry<E> registry, final Function<S, E> entryFunction, final S state)
	{
		final NbtCompound compound = new NbtCompound();
		compound.putString("Name", registry.getId(entryFunction.apply(state)).toString());
		final ImmutableMap<Property<?>, Comparable<?>> entries = state.getEntries();
		
		if (!entries.isEmpty())
		{
			final NbtCompound properties = new NbtCompound();
			
			for (final Entry<Property<?>, Comparable<?>> entry : entries.entrySet())
			{
				@SuppressWarnings("rawtypes")
				final Property property = entry.getKey();
				@SuppressWarnings("unchecked")
				final String valueName = property.name(entry.getValue());
				properties.putString(property.getName(), valueName);
			}
			
			compound.put("Properties", properties);
		}
		
		return compound;
	}
	
	public static void setupIdRemapCallbacks()
	{
		RegistryIdRemapCallback.event(Registry.BLOCK).register(s -> StateRefresher.INSTANCE.reorderBlockStates());
		RegistryIdRemapCallback.event(Registry.FLUID).register(s -> StateRefresher.INSTANCE.reorderFluidStates());
	}
}
