package mcmultipart.block;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import mcmultipart.MCMultiPart;
import mcmultipart.api.container.IMultipartContainer;
import mcmultipart.api.container.IPartInfo;
import mcmultipart.api.multipart.IMultipart;
import mcmultipart.api.multipart.IMultipartTile;
import mcmultipart.api.multipart.MultipartHelper;
import mcmultipart.api.multipart.MultipartOcclusionHelper;
import mcmultipart.api.ref.MCMPCapabilities;
import mcmultipart.api.slot.IPartSlot;
import mcmultipart.api.slot.SlotUtil;
import mcmultipart.capability.CapabilityJoiner;
import mcmultipart.client.TESRMultipartContainer;
import mcmultipart.multipart.MultipartRegistry;
import mcmultipart.multipart.PartInfo;
import mcmultipart.network.MultipartNetworkHandler;
import mcmultipart.network.PacketMultipartAdd;
import mcmultipart.network.PacketMultipartRemove;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.Mirror;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.GameData;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class TileMultipartContainer extends TileEntity implements IMultipartContainer {

    private boolean isInWorld = true;
    private final Map<IPartSlot, PartInfo> parts = new HashMap<>();
    private Map<IPartSlot, NBTTagCompound> missingParts;
    private World loadingWorld;
    private boolean notifyClients = true;

    private TileMultipartContainer(World world, BlockPos pos) {
        setWorld(world);
        setPos(pos);
        isInWorld = false;
    }

    public TileMultipartContainer() {
    }

    @Override
    public void setWorld(World world) {
        super.setWorld(world);
        isInWorld = true;
        forEachTile(te -> te.setWorld(world));
    }

    @Override
    protected void setWorldCreate(World world) {
        loadingWorld = world;
    }

    @Override
    public void setPos(BlockPos pos) {
        super.setPos(pos);
        forEachTile(te -> te.setPos(pos));
    }

    @Override
    public Optional<IPartInfo> get(IPartSlot slot) {
        return Optional.ofNullable(parts.get(slot));
    }

    @Override
    public boolean canAddPart(IPartSlot slot, IBlockState state, IMultipartTile tile) {
        Preconditions.checkNotNull(slot);
        Preconditions.checkNotNull(state);

        IMultipart part = MultipartRegistry.INSTANCE.getPart(state.getBlock());
        Preconditions.checkState(part != null, "The blockstate " + state + " could not be converted to a multipart!");
        PartInfo info = new PartInfo(this, slot, part, state, tile);

        // If any of the slots required by this multipart aren't empty, fail.
        Set<IPartSlot> partSlots = Sets.newIdentityHashSet();
        partSlots.addAll(part.getGhostSlots(info));
        partSlots.add(slot);
        if (partSlots.stream().anyMatch(parts::containsKey)
                || parts.values().stream().map(i -> i.getPart().getGhostSlots(i)).flatMap(Set::stream).anyMatch(partSlots::contains)) {
            partSlots.clear();
            return false;
        }
        partSlots.clear();

        // If the occlusion boxes of this part intesect with any other parts', fail.
        if (MultipartOcclusionHelper.testContainerPartIntersection(this, info)) {
            return false;
        }

        return true;
    }

    @Override
    public void addPart(IPartSlot slot, IBlockState state, IMultipartTile tile) {
        if ((tile != null && tile.isTickable() && !(this instanceof TileMultipartContainer.Ticking)) || !isInWorld) {
            getWorld().setBlockState(getPos(),
                    MCMultiPart.multipart.getDefaultState().withProperty(BlockMultipartContainer.PROPERTY_TICKING,
                            this instanceof TileMultipartContainer.Ticking || (tile != null && tile.isTickable())));
            TileMultipartContainer container = (TileMultipartContainer) MultipartHelper.getContainer(getWorld(), getPos()).get();
            copyTo(container);
            container.notifyClients = false;
            try {
                container.addPart(slot, state, tile);
            } finally {
                container.notifyClients = true;
            }

            return;
        }

        IMultipart part = MultipartRegistry.INSTANCE.getPart(state.getBlock());
        Preconditions.checkState(part != null, "The blockstate " + state + " could not be converted to a multipart!");

        addPartDo(slot, part, state, tile, true);
    }

    private void addPartDo(IPartSlot slot, IMultipart part, IBlockState state, IMultipartTile tile, boolean notify) {
        PartInfo info = new PartInfo(this, slot, part, state, tile);
        add(slot, info);
        if (missingParts != null) {
            missingParts.remove(slot);
        }

        if (tile != null) {
            tile.validate();
        }

        if (notify && !getWorld().isRemote) {
            info.getPart().onAdded(info);
            parts.values().forEach(i -> {
                if (i != info) {
                    i.getPart().onPartAdded(i, info);
                }
            });
            IBlockState st = getWorld().getBlockState(getPos());
            getWorld().notifyBlockUpdate(getPos(), st, st, 1);
            getWorld().checkLight(getPos());
            if (notifyClients) {
                MultipartNetworkHandler.sendToAllWatching(new PacketMultipartAdd(info), getWorld(), getPos());
            }
        }
    }

    @Override
    public void removePart(IPartSlot slot) {
        Optional<IPartInfo> prev = get(slot);
        if (!prev.isPresent()) {
            return;
        }
        remove(slot);

        IPartInfo info = prev.get();
        info.getPart().onRemoved(info);
        parts.values().forEach(i -> i.getPart().onPartRemoved(i, info));

        IBlockState state = getWorld().getBlockState(getPos()), newState = state;
        if (parts.size() == 1) {
            PartInfo part = parts.values().iterator().next();
            newState = part.getState();
            getWorld().setBlockState(getPos(), part.getState(), 0);
            if (part.getTile() != null) {
                getWorld().removeTileEntity(getPos());
                TileEntity te = part.getTile().getTileEntity();
                te.validate();
                getWorld().setTileEntity(getPos(), te);
            }
        } else if (info.getTile() != null && info.getTile().isTickable() && !hasTickingParts()) {
            newState = MCMultiPart.multipart.getDefaultState().withProperty(BlockMultipartContainer.PROPERTY_TICKING, false);
            getWorld().setBlockState(getPos(), newState, 0);
            TileMultipartContainer container = (TileMultipartContainer) MultipartHelper.getContainer(getWorld(), getPos()).get();
            copyTo(container);
        }
        if (!getWorld().isRemote) {
            getWorld().markAndNotifyBlock(getPos(), getWorld().getChunkFromBlockCoords(getPos()), state, newState, 3);
            getWorld().checkLight(getPos());
            MultipartNetworkHandler.sendToAllWatching(new PacketMultipartRemove(getPos(), slot), getWorld(), getPos());
        } else {
            getWorld().markAndNotifyBlock(getPos(), getWorld().getChunkFromBlockCoords(getPos()), state, newState, 2);
            getWorld().checkLight(getPos());
        }
    }

    private boolean hasTickingParts() {
        return parts.values().stream().map(IPartInfo::getTile).filter(t -> t != null && t.isTickable()).count() != 0;
    }

    protected void add(IPartSlot slot, PartInfo partInfo) {
        parts.put(slot, partInfo);
    }

    protected void remove(IPartSlot slot) {
        parts.remove(slot);
    }

    protected void copyTo(TileMultipartContainer container) {
        parts.forEach(container::add);
        if (missingParts != null) {
            container.missingParts = missingParts;
        }
        container.parts.values().forEach(i -> i.setContainer(container));
    }

    @Override
    public Map<IPartSlot, PartInfo> getParts() {
        return parts;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag = super.writeToNBT(tag);
        tag = writeParts(tag, false);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readParts(tag, false, loadingWorld);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        writeParts(tag, true);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readParts(tag, true, getWorld());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(getPos(), 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    private NBTTagCompound writeParts(NBTTagCompound tag, boolean update) {
        NBTTagCompound parts = new NBTTagCompound();
        this.parts.forEach((s, i) -> {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("state", MCMultiPart.stateMap.get(i.getState()));
            IMultipartTile tile = i.getTile();
            if (tile != null) {
                if (update) {
                    t.setTag("tile", tile.getUpdateTag());
                } else {
                    t.setTag("tile", tile.writeToNBT(new NBTTagCompound()));
                }
            }
            parts.setTag(Integer.toString(MCMultiPart.slotRegistry.getId(s)), t);
        });
        if (this.missingParts != null) {
            this.missingParts.forEach((s, t) -> parts.setTag(Integer.toString(MCMultiPart.slotRegistry.getId(s)), t));
        }
        tag.setTag("parts", parts);
        return tag;
    }

    private void readParts(NBTTagCompound tag, boolean update, World world) {
        World prevWorld = this.world;
        this.world = world;
        ObjectIntIdentityMap<IBlockState> stateMap = GameData.getBlockStateIDMap();
        NBTTagCompound parts = tag.getCompoundTag("parts");
        this.parts.clear();
        for (String sID : parts.getKeySet()) {
            IPartSlot slot = MCMultiPart.slotRegistry.getObjectById(Integer.parseInt(sID));
            if (slot != null) {
                NBTTagCompound t = parts.getCompoundTag(sID);
                IBlockState state = stateMap.getByValue(t.getInteger("state"));
                IMultipart part = MultipartRegistry.INSTANCE.getPart(state.getBlock());
                if (part != null) {
                    IMultipartTile tile = null;
                    if (t.hasKey("tile")) {
                        NBTTagCompound tileTag = t.getCompoundTag("tile");
                        if (update) {
                            tile = part.createMultipartTile(world, slot, state);
                            tile.setWorld(world);
                            tile.handleUpdateTag(tileTag);
                        } else {
                            tile = part.loadMultipartTile(world, tileTag);
                        }
                    }
                    add(slot, new PartInfo(this, slot, part, state, tile));
                } else if (!update) {
                    if (missingParts == null) {
                        missingParts = new HashMap<>();
                    }
                    missingParts.put(slot, t);
                }
            }
        }
        this.world = prevWorld;
    }

    @Override
    public void onLoad() {
        forEachTile(te -> {
            te.setWorld(getWorld());
            te.setPos(getPos());
        });
        forEachTile(IMultipartTile::onLoad);
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        forEachTile(IMultipartTile::onChunkUnload);
    }

    @Override
    public void mirror(Mirror mirror) {
        super.mirror(mirror);
        forEachTile(te -> te.mirror(mirror));
    }

    @Override
    public void rotate(Rotation rotation) {
        super.rotate(rotation);
        forEachTile(te -> te.rotate(rotation));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        forEachTile(IMultipartTile::invalidate);
    }

    @Override
    public void validate() {
        super.validate();
        forEachTile(IMultipartTile::validate);
    }

    @Override
    public void updateContainingBlockInfo() {
        super.updateContainingBlockInfo();
        forEachTile(IMultipartTile::updateContainingBlockInfo);
    }

    @Override
    public double getMaxRenderDistanceSquared() {
        return parts.values().stream().map(IPartInfo::getTile).filter(t -> t != null)
                .mapToDouble(IMultipartTile::getMaxRenderDistanceSquared).max().orElse(super.getMaxRenderDistanceSquared());
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return parts.values().stream().map(IPartInfo::getTile).filter(t -> t != null)//
                .reduce(super.getRenderBoundingBox(), (a, b) -> a.union(b.getRenderBoundingBox()), (a, b) -> b);
    }

    @Override
    public boolean canRenderBreaking() {
        return true;
    }

    @Override
    public boolean hasFastRenderer() {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            return hasFastRendererC();
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    private boolean hasFastRendererC() {
        for (IPartInfo info : parts.values()) {
            TileEntity te = info.getTile() != null ? info.getTile().getTileEntity() : null;
            if (te != null && TileEntityRendererDispatcher.instance.getSpecialRenderer(te) != null && !te.hasFastRenderer()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            shouldRenderInPassC(pass);
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    public void shouldRenderInPassC(int pass) {
        TESRMultipartContainer.pass = pass;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == MCMPCapabilities.MULTIPART_CONTAINER) {
            return true;
        }
        if (SlotUtil.viewContainer(this, i -> i.getTile() != null && i.getTile().hasCapability(capability, facing),
                l -> l.stream().anyMatch(a -> a), false, true, facing)) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == MCMPCapabilities.MULTIPART_CONTAINER) {
            return (T) this;
        }
        T val = SlotUtil.viewContainer(
                this, i -> i.getTile() != null && i.getTile().hasCapability(capability, facing)
                        ? i.getTile().getCapability(capability, facing) : null,
                l -> CapabilityJoiner.join(capability, l), null, true, facing);
        if (val != null) {
            return val;
        }
        return super.getCapability(capability, facing);
    }

    protected void forEachTile(Consumer<IMultipartTile> consumer) {
        for (PartInfo info : getParts().values()) {
            IMultipartTile tile = info.getTile();
            if (tile != null) {
                consumer.accept(tile);
            }
        }
    }

    public static class Ticking extends TileMultipartContainer implements ITickable {

        private static final Object obj = new Object();
        private final Map<ITickable, Object> tickingParts = new WeakHashMap<>();

        private Ticking(World world, BlockPos pos) {
            super(world, pos);
        }

        public Ticking() {
        }

        @Override
        public void update() {
            if (tickingParts.isEmpty()) {
                getWorld().setBlockState(getPos(),
                        MCMultiPart.multipart.getDefaultState().withProperty(BlockMultipartContainer.PROPERTY_TICKING, false));
                TileMultipartContainer container = (TileMultipartContainer) MultipartHelper.getContainer(getWorld(), getPos()).get();
                copyTo(container);
                getWorld().checkLight(getPos());
                return;
            }
            tickingParts.keySet().forEach(ITickable::update);
        }

        @Override
        protected void add(IPartSlot slot, PartInfo partInfo) {
            super.add(slot, partInfo);
            IMultipartTile te = partInfo.getTile();
            if (te != null && te.isTickable()) {
                tickingParts.put(te.getTickable(), obj);
            }
        }

        @Override
        protected void remove(IPartSlot slot) {
            IMultipartTile te = getPartTile(slot).orElse(null);
            if (te != null && te.isTickable()) {
                tickingParts.remove(te);
            }
            super.remove(slot);
        }

        @Override
        protected void copyTo(TileMultipartContainer container) {
            super.copyTo(container);
            if (container instanceof TileMultipartContainer.Ticking) {
                ((Ticking) container).tickingParts.putAll(tickingParts);
            }
        }

    }

    public static IMultipartContainer createTile(World world, BlockPos pos) {
        return new TileMultipartContainer(world, pos);
    }

    public static IMultipartContainer createTileFromWorldInfo(World world, BlockPos pos) {
        PartInfo info = PartInfo.fromWorld(world, pos);
        TileMultipartContainer tmc = info.getTile() != null && info.getTile().isTickable() ? new TileMultipartContainer.Ticking(world, pos)
                : new TileMultipartContainer(world, pos);
        if (tmc.canAddPart(info.getSlot(), info.getState(), info.getTile())) {
            if (info.getTile() != null) {
                info.getTile().invalidate();
            }
            tmc.addPartDo(info.getSlot(), info.getPart(), info.getState(), info.getTile(), false);
            tmc.parts.get(info.getSlot()).copyMetaFrom(info);
        }
        return tmc;
    }

}
