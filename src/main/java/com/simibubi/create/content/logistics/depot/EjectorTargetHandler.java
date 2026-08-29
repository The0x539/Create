package com.simibubi.create.content.logistics.depot;

import net.createmod.catnip.platform.CatnipServices;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(value = Dist.CLIENT)
public class EjectorTargetHandler {

	static BlockPos selectedTarget;
	static EjectorBlockEntity selectedEjector;
	static ItemStack currentItem;
	static long lastHoveredBlockPos = -1;
	static EntityLauncher launcher;

	@SubscribeEvent
	public static void rightClickingBlocksSelectsThem(PlayerInteractEvent.RightClickBlock event) {
		if (currentItem == null)
			return;
		BlockPos pos = event.getPos();
		Level world = event.getLevel();
		if (!world.isClientSide)
			return;
		Player player = event.getEntity();
		if (player == null || player.isSpectator())
			return;

		if (!player.isShiftKeyDown() && selectedEjector == null)
			return;

		String key = "weighted_ejector.target_set";
		ChatFormatting colour = ChatFormatting.GOLD;
		player.displayClientMessage(CreateLang.translateDirect(key)
			.withStyle(colour), true);
		selectedTarget = pos;
		launcher = null;
		if (selectedEjector != null) {
			flushSettings(selectedEjector.getBlockPos());
		}
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	@SubscribeEvent
	public static void leftClickingBlocksDeselectsThem(PlayerInteractEvent.LeftClickBlock event) {
		if (currentItem == null)
			return;
		if (!event.getLevel().isClientSide)
			return;
		if (!event.getEntity()
			.isShiftKeyDown())
			return;
		BlockPos pos = event.getPos();
		if (pos.equals(selectedTarget)) {
			selectedTarget = null;
			launcher = null;
			event.setCanceled(true);
		}
	}

	public static void flushSettings(BlockPos ejectorPos) {
		int h = 0;
		int v = 0;

		LocalPlayer player = Minecraft.getInstance().player;
		String key = "weighted_ejector.target_not_valid";
		ChatFormatting colour = ChatFormatting.WHITE;

		if (selectedTarget == null) {
			key = "weighted_ejector.no_target";
		} else if (selectedEjector != null) {
			key = "weighted_ejector.new_target_not_valid";
		}

		Direction validTargetDirection = getValidTargetDirection(ejectorPos, selectedTarget);
		if (validTargetDirection == null) {
			player.displayClientMessage(CreateLang.translateDirect(key)
				.withStyle(colour), true);
			currentItem = null;
			selectedTarget = null;
			selectedEjector = null;
			return;
		}

		key = "weighted_ejector.targeting";
		colour = ChatFormatting.GREEN;

		player.displayClientMessage(
			CreateLang.translateDirect(key, selectedTarget.getX(), selectedTarget.getY(), selectedTarget.getZ())
				.withStyle(colour),
			true);

		BlockPos diff = ejectorPos.subtract(selectedTarget);
		h = Math.abs(diff.getX() + diff.getZ());
		v = -diff.getY();

		CatnipServices.NETWORK.sendToServer(new EjectorPlacementPacket(h, v, ejectorPos, validTargetDirection));
		selectedTarget = null;
		selectedEjector = null;
		currentItem = null;

	}

	public static Direction getValidTargetDirection(BlockPos ejector, BlockPos target) {
		if (target == null)
			return null;
		if (VecHelper.onSameAxis(ejector, target, Axis.Y))
			return null;

		int xDiff = target.getX() - ejector.getX();
		int zDiff = target.getZ() - ejector.getZ();
		int max = AllConfigs.server().kinetics.maxEjectorDistance.get();

		if (Math.abs(xDiff) > max || Math.abs(zDiff) > max)
			return null;

		if (xDiff == 0)
			return Direction.get(zDiff < 0 ? AxisDirection.NEGATIVE : AxisDirection.POSITIVE, Axis.Z);
		if (zDiff == 0)
			return Direction.get(xDiff < 0 ? AxisDirection.NEGATIVE : AxisDirection.POSITIVE, Axis.X);

		return null;
	}

	public static void tick() {
		Player player = Minecraft.getInstance().player;

		if (player == null)
			return;

		ItemStack heldItemMainhand = player.getMainHandItem();
		if (heldItemMainhand != currentItem) {
			if (AllBlocks.WEIGHTED_EJECTOR.isIn(heldItemMainhand)) {
				selectedTarget = null;
				currentItem = heldItemMainhand;
			} else if (currentItem != null) {
				selectedTarget = null;
				currentItem = null;
			}

			selectedEjector = null;
		}

		if (selectedEjector != null && selectedEjector.isRemoved()) {
			currentItem = null;
			selectedTarget = null;
			selectedEjector = null;
		}

		if (currentItem != null) {
			if (selectedEjector != null) {
				drawOutline(selectedEjector.getBlockPos());
			} else {
				drawOutline(selectedTarget);
			}
		} else {
			checkForWrench(heldItemMainhand);
		}
		drawArc();
	}

	protected static Vec3i snapToVerticalPlane(Vec3i v) {
		int x = v.getX(), y = v.getY(), z = v.getZ();
		return switch (Integer.compare(Math.abs(x), Math.abs(z))) {
			case 1 -> new Vec3i(x, y, 0);
			case 0 -> new Vec3i(0, y, 0);
			case -1 -> new Vec3i(0, y, z);
			default -> v;
		};
	}

	protected static void drawArc() {
		Minecraft mc = Minecraft.getInstance();
		boolean wrench = AllItems.WRENCH.isIn(mc.player.getMainHandItem());
		boolean reconfiguring = selectedEjector != null;

		if (selectedTarget == null && selectedEjector == null)
			return;
		if (currentItem == null && !wrench)
			return;

		HitResult objectMouseOver = mc.hitResult;
		if (!(objectMouseOver instanceof BlockHitResult blockRayTraceResult))
			return;
		if (blockRayTraceResult.getType() == Type.MISS)
			return;

		BlockPos mousePos = blockRayTraceResult.getBlockPos();
		if (!wrench)
			mousePos = mousePos.relative(blockRayTraceResult.getDirection());

		BlockPos selectedPos = reconfiguring ? selectedEjector.getBlockPos() : selectedTarget;

		Vec3i mouseOffset = mousePos.subtract(selectedPos);
		Vec3i validOffset = snapToVerticalPlane(mouseOffset);
		BlockPos validPos = selectedPos.offset(validOffset);
		BlockPos ejectorPos, targetPos;
		if (reconfiguring) {
			ejectorPos = selectedPos;
			targetPos = validPos;
		} else {
			ejectorPos = validPos;
			targetPos = selectedPos;
		}
		
		Direction d = getValidTargetDirection(ejectorPos, targetPos);
		if (d == null)
			return;

		if (launcher == null || lastHoveredBlockPos != mousePos.asLong()) {
			lastHoveredBlockPos = mousePos.asLong();
			int horizontalDistance = Math.abs(validOffset.getX() + validOffset.getZ());
			launcher = new EntityLauncher(horizontalDistance, validOffset.getY());
		}

		double totalFlyingTicks = launcher.getTotalFlyingTicks() + 3;
		int segments = (((int) totalFlyingTicks) / 3) + 1;
		double tickOffset = totalFlyingTicks / segments;
		boolean valid = mouseOffset.equals(validOffset);
		Color color = new Color(valid ? 0x9ede73 : 0xff7171);
		DustParticleOptions data = new DustParticleOptions(color.asVectorF(), 1);
		ClientLevel world = mc.level;

		AABB bb;
		if (reconfiguring) {
			BlockState state = world.getBlockState(validPos);
			VoxelShape shape = state.getShape(world, validPos);
			bb = shape.isEmpty() ? new AABB(BlockPos.ZERO) : shape.bounds();
		} else {
			bb = new AABB(0, 0, 0, 1, 13 / 16f, 1);
		}
		Outliner.getInstance().chaseAABB("valid", bb.move(validPos))
			.colored(color)
			.lineWidth(1 / 16f);

		for (int i = 0; i < segments; i++) {
			double ticks = ((AnimationTickHolder.getRenderTime() / 3) % tickOffset) + i * tickOffset;
			Vec3 vec = launcher.getGlobalPos(ticks, d.getOpposite(), ejectorPos);
			world.addParticle(data, vec.x, vec.y, vec.z, 0, 0, 0);
		}
	}

	private static void checkForWrench(ItemStack heldItem) {
		if (!AllItems.WRENCH.isIn(heldItem))
			return;
		HitResult objectMouseOver = Minecraft.getInstance().hitResult;
		if (!(objectMouseOver instanceof BlockHitResult result))
			return;
		BlockPos pos = result.getBlockPos();

		BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
		if (!(be instanceof EjectorBlockEntity)) {
			lastHoveredBlockPos = -1;
			selectedTarget = null;
			return;
		}

		if (lastHoveredBlockPos == -1 || lastHoveredBlockPos != pos.asLong()) {
			EjectorBlockEntity ejector = (EjectorBlockEntity) be;
			if (!ejector.getTargetPosition()
				.equals(ejector.getBlockPos()))
				selectedTarget = ejector.getTargetPosition();
			lastHoveredBlockPos = pos.asLong();
			launcher = null;
		}

		if (lastHoveredBlockPos != -1)
			drawOutline(selectedTarget);
	}

	public static void drawOutline(BlockPos selection) {
		Level world = Minecraft.getInstance().level;
		if (selection == null)
			return;

		BlockPos pos = selection;
		BlockState state = world.getBlockState(pos);
		VoxelShape shape = state.getShape(world, pos);
		AABB boundingBox = shape.isEmpty() ? new AABB(BlockPos.ZERO) : shape.bounds();
		Outliner.getInstance().showAABB("target", boundingBox.move(pos))
			.colored(0xffcb74)
			.lineWidth(1 / 16f);
	}

	public static void beginReconfigure(EjectorBlockEntity ejector, ItemStack wrench, Player player) {
		selectedTarget = null;
		selectedEjector = ejector;
		currentItem = wrench;
		CreateLang.builder()
			.translate("weighted_ejector.reconfigure")
			.style(ChatFormatting.WHITE)
			.sendStatus(player);
	}

}
