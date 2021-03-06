package mcjty.rftools.blocks.powercell;

import cofh.redstoneflux.api.IEnergyReceiver;
import mcjty.lib.api.MachineInformation;
import mcjty.lib.api.smartwrench.SmartWrenchSelector;
import mcjty.lib.bindings.DefaultAction;
import mcjty.lib.bindings.IAction;
import mcjty.lib.container.DefaultSidedInventory;
import mcjty.lib.container.InventoryHelper;
import mcjty.lib.tileentity.GenericTileEntity;
import mcjty.lib.typed.Key;
import mcjty.lib.typed.Type;
import mcjty.lib.typed.TypedMap;
import mcjty.lib.varia.BlockPosTools;
import mcjty.lib.varia.EnergyTools;
import mcjty.lib.varia.GlobalCoordinate;
import mcjty.lib.varia.Logging;
import mcjty.rftools.items.powercell.PowerCellCardItem;
import net.darkhax.tesla.api.ITeslaConsumer;
import net.darkhax.tesla.api.ITeslaHolder;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

import static mcjty.rftools.blocks.powercell.PowerCellConfiguration.advancedFactor;
import static mcjty.rftools.blocks.powercell.PowerCellConfiguration.simpleFactor;

@Optional.Interface(iface = "cofh.redstoneflux.api.IEnergyReceiver", modid = "redstoneflux")
public class PowerCellTileEntity extends GenericTileEntity implements IEnergyReceiver,
        DefaultSidedInventory, ITickable, SmartWrenchSelector, MachineInformation {

    public static final String CMD_GET_INFO = "getInfo";
    public static final Key<Integer> PARAM_ENERGY = new Key<>("energy", Type.INTEGER);
    public static final Key<Integer> PARAM_BLOCKS = new Key<>("block", Type.INTEGER);
    public static final Key<Integer> PARAM_SIMPLEBLOCKS = new Key<>("simpleblocks", Type.INTEGER);
    public static final Key<Integer> PARAM_ADVANCEDBLOCKS = new Key<>("advancedblocks", Type.INTEGER);
    public static final Key<Long> PARAM_TOTAL_INSERTED = new Key<>("totalinserted", Type.LONG);
    public static final Key<Long> PARAM_TOTAL_EXTRACTED = new Key<>("totalextracted", Type.LONG);
    public static final Key<Integer> PARAM_RFPERTICK = new Key<>("rfpertick", Type.INTEGER);
    public static final Key<Double> PARAM_COSTFACTOR = new Key<>("costfactor", Type.DOUBLE);

    public static final String ACTION_SETNONE = "setNone";
    public static final String ACTION_SETINPUT = "setInput";
    public static final String ACTION_SETOUTPUT = "setOutput";
    public static final String ACTION_CLEARSTATS = "clearStats";

    // Client side for tooltip purposes
    public static int tooltipEnergy = 0;
    public static int tooltipBlocks = 0;
    public static int tooltipSimpleBlocks = 0;
    public static int tooltipAdvancedBlocks = 0;
    public static long tooltipInserted = 0;
    public static long tooltipExtracted = 0;
    public static int tooltipRfPerTick = 0;
    public static float tooltipCostFactor = 0;

    @Override
    public IAction[] getActions() {
        return new IAction[] {
                new DefaultAction(ACTION_SETNONE, this::setAllNone),
                new DefaultAction(ACTION_SETINPUT, this::setAllInput),
                new DefaultAction(ACTION_SETOUTPUT, this::setAllOutput),
                new DefaultAction(ACTION_CLEARSTATS, () -> {
                    this.totalExtracted = 0;
                    this.totalInserted = 0;
                    this.markDirty();
                }),
        };
    }

    private static final String[] TAGS = new String[]{"rfpertick_out", "rfpertick_in", "rftotal_in", "rftotal_out"};
    private static final String[] TAG_DESCRIPTIONS = new String[] {
            "The current RF/t output given by this block (last 2 seconds)",
            "The current RF/t input received by this block (last 2 seconds)",
            "The total RF/t output given by this block",
            "The current RF/t input received by this block"};

    private InventoryHelper inventoryHelper = new InventoryHelper(this, PowerCellContainer.factory, 3);

    private int networkId = -1;

    // Only used when this block is not part of a network
    private int energy = 0;

    // Total amount of energy extracted from this block (local or not)
    private long totalExtracted = 0;
    // Total amount of energy inserted in this block (local or not)
    private long totalInserted = 0;

    private int lastRfPerTickIn = 0;
    private int lastRfPerTickOut = 0;
    private int powerIn = 0;
    private int powerOut = 0;
    private long lastTime = 0;

    public enum Mode implements IStringSerializable {
        MODE_NONE("none"),
        MODE_INPUT("input"),   // Blue
        MODE_OUTPUT("output"); // Yellow

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
    private Mode modes[] = new Mode[] { Mode.MODE_NONE, Mode.MODE_NONE, Mode.MODE_NONE, Mode.MODE_NONE, Mode.MODE_NONE, Mode.MODE_NONE };

    public PowerCellTileEntity() {
        super();
    }

    @Override
    public int getTagCount() {
        return TAGS.length;
    }

    @Override
    public String getTagName(int index) {
        return TAGS[index];
    }

    @Override
    public String getTagDescription(int index) {
        return TAG_DESCRIPTIONS[index];
    }

    @Override
    public String getData(int index, long millis) {
        switch (index) {
            case 0: return lastRfPerTickOut + "RF/t";
            case 1: return lastRfPerTickIn + "RF/t";
            case 2: return totalExtracted + "RF";
            case 3: return totalInserted + "RF";
        }
        return null;
    }

    public int getLastRfPerTickIn() {
        return lastRfPerTickIn;
    }

    public int getLastRfPerTickOut() {
        return lastRfPerTickOut;
    }

    @Override
    protected boolean needsCustomInvWrapper() {
        return true;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
        markDirty();
    }

    @Nullable
    public PowerCellNetwork.Network getNetwork() {
        if (world.isRemote) {
            // Safety
            return null;
        }

        int networkId = getNetworkId();
        if (networkId == -1) {
            return null;
        }
        PowerCellNetwork generatorNetwork = PowerCellNetwork.getChannels(getWorld());
        return generatorNetwork.getOrCreateNetwork(networkId);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        Mode[] old = new Mode[] { modes[0], modes[1], modes[2], modes[3], modes[4], modes[5] };
        super.onDataPacket(net, packet);
        for (int i = 0 ; i < 6 ; i++) {
            if (old[i] != modes[i]) {
                getWorld().markBlockRangeForRenderUpdate(getPos(), getPos());
                return;
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound) {
        super.readFromNBT(tagCompound);
    }

    @Override
    public void readRestorableFromNBT(NBTTagCompound tagCompound) {
        super.readRestorableFromNBT(tagCompound);
        readBufferFromNBT(tagCompound, inventoryHelper);
        energy = tagCompound.getInteger("energy");
        totalInserted = tagCompound.getLong("totIns");
        totalExtracted = tagCompound.getLong("totExt");
        networkId = tagCompound.getInteger("networkId");
        modes[0] = Mode.values()[tagCompound.getByte("m0")];
        modes[1] = Mode.values()[tagCompound.getByte("m1")];
        modes[2] = Mode.values()[tagCompound.getByte("m2")];
        modes[3] = Mode.values()[tagCompound.getByte("m3")];
        modes[4] = Mode.values()[tagCompound.getByte("m4")];
        modes[5] = Mode.values()[tagCompound.getByte("m5")];
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound) {
        super.writeToNBT(tagCompound);
        return tagCompound;
    }

    @Override
    public void writeRestorableToNBT(NBTTagCompound tagCompound) {
        super.writeRestorableToNBT(tagCompound);
        writeBufferToNBT(tagCompound, inventoryHelper);
        tagCompound.setInteger("energy", energy);
        tagCompound.setLong("totIns", totalInserted);
        tagCompound.setLong("totExt", totalExtracted);
        tagCompound.setInteger("networkId", networkId);
        tagCompound.setByte("m0", (byte) modes[0].ordinal());
        tagCompound.setByte("m1", (byte) modes[1].ordinal());
        tagCompound.setByte("m2", (byte) modes[2].ordinal());
        tagCompound.setByte("m3", (byte) modes[3].ordinal());
        tagCompound.setByte("m4", (byte) modes[4].ordinal());
        tagCompound.setByte("m5", (byte) modes[5].ordinal());
    }

    public Mode getMode(EnumFacing side) {
        return modes[side.ordinal()];
    }

    public void toggleMode(EnumFacing side) {
        switch (modes[side.ordinal()]) {
            case MODE_NONE:
                modes[side.ordinal()] = Mode.MODE_INPUT;
                break;
            case MODE_INPUT:
                modes[side.ordinal()] = Mode.MODE_OUTPUT;
                break;
            case MODE_OUTPUT:
                modes[side.ordinal()] = Mode.MODE_NONE;
                break;
        }
        markDirtyClient();
    }

    @Override
    public void update() {
        if (!getWorld().isRemote) {
            long time = world.getTotalWorldTime();
            if (lastTime == 0) {
                lastTime = time;
            } else if (time > lastTime + 40) {
                lastRfPerTickIn = (int) (powerIn / (time - lastTime));
                lastRfPerTickOut = (int) (powerOut / (time - lastTime));
                lastTime = time;
                powerIn = 0;
                powerOut = 0;
            }

            if (isCreative()) {
                // A creative powercell automatically generates 1000000 RF/tick
                int gain = 1000000;
                int networkId = getNetworkId();
                if (networkId == -1) {
                    receiveEnergyLocal(gain, false);
                } else {
                    receiveEnergyMulti(gain, false);
                }
            }

            int energyStored = getEnergyStored();
            if (energyStored <= 0) {
                return;
            }

            handleChargingItem();
            sendOutEnergy();
        }
    }

    private void handleChargingItem() {
        ItemStack stack = inventoryHelper.getStackInSlot(PowerCellContainer.SLOT_CHARGEITEM);
        if (stack.isEmpty()) {
            return;
        }

        int rfToGive = Math.min(PowerCellConfiguration.CHARGEITEMPERTICK, getEnergyStored());
        int received = (int)EnergyTools.receiveEnergy(stack, rfToGive);
        if (received == 0) {
            return;
        }
        extractEnergyInternal(received, false, PowerCellConfiguration.CHARGEITEMPERTICK);
    }

    private void sendOutEnergy() {
        int energyStored = getEnergyStored();

        for (EnumFacing face : EnumFacing.VALUES) {
            if (modes[face.ordinal()] == Mode.MODE_OUTPUT) {
                BlockPos pos = getPos().offset(face);
                TileEntity te = getWorld().getTileEntity(pos);
                EnumFacing opposite = face.getOpposite();
                if (EnergyTools.isEnergyTE(te, opposite)) {
                    // If the adjacent block is also a powercell then we only send energy if this cell is local or the other cell has a different id
                    if ((!(te instanceof PowerCellTileEntity)) || getNetworkId() == -1 || ((PowerCellTileEntity) te).getNetworkId() != getNetworkId()) {
                        float factor = getCostFactor();
                        int rfPerTick = getRfPerTickPerSide();
                        int rfToGive = Math.min(rfPerTick, (int) (energyStored / factor));

                        int received = (int) EnergyTools.receiveEnergy(te, opposite, rfToGive);

                        energyStored -= extractEnergyInternal(received, false, Integer.MAX_VALUE);
                        if (energyStored <= 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    public float getCostFactor() {
        float infusedFactor = getInfusedFactor();

        float factor;
        if (getNetworkId() == -1) {
            factor = 1.0f; // Local energy
        } else {
            factor = getNetwork().calculateCostFactor(getWorld(), getGlobalPos());
            factor = (factor - 1) * (1-infusedFactor/2) + 1;
        }
        return factor;
    }

    public int getRfPerTickPerSide() {
        return (int) (PowerCellConfiguration.rfPerTick * getPowerFactor() / simpleFactor * (getInfusedFactor()*.5+1));
    }

    private void handleCardRemoval() {
        if (!getWorld().isRemote) {
            PowerCellNetwork.Network network = getNetwork();
            if (network != null) {
                energy = network.extractEnergySingleBlock(isAdvanced(), isSimple());
                network.remove(getWorld(), getGlobalPos(), isAdvanced(), isSimple());
                PowerCellNetwork.getChannels(getWorld()).save();
            }
        }
        networkId = -1;
        markDirty();
    }

    private void handleCardInsertion() {
        ItemStack stack = inventoryHelper.getStackInSlot(PowerCellContainer.SLOT_CARD);
        int id = PowerCellCardItem.getId(stack);
        if (!getWorld().isRemote) {
            PowerCellNetwork channels = PowerCellNetwork.getChannels(getWorld());
            if (id == -1) {
                id = channels.newChannel();
                PowerCellCardItem.setId(stack, id);
            }
            networkId = id;
            PowerCellNetwork.Network network = getNetwork();
            network.add(getWorld(), getGlobalPos(), isAdvanced(), isSimple());
            network.receiveEnergy(energy);
            channels.save();
        } else {
            networkId = id;
        }
        markDirty();
    }

    private boolean isAdvanced() {
        return PowerCellBlock.isAdvanced(getWorld().getBlockState(getPos()).getBlock());
    }

    private boolean isSimple() {
        return PowerCellBlock.isSimple(getWorld().getBlockState(getPos()).getBlock());
    }

    private boolean isCreative() {
        return PowerCellBlock.isCreative(getWorld().getBlockState(getPos()).getBlock());
    }

    // Get the power factor relative to the simple powercell
    private int getPowerFactor() {
        if (isSimple()) {
            return 1;
        }
        return isAdvanced() ? (advancedFactor * simpleFactor) : simpleFactor;
    }

    public int getEnergy() {
        return energy;
    }

    public GlobalCoordinate getGlobalPos() {
        return new GlobalCoordinate(getPos(), getWorld().provider.getDimension());
    }

    @Override
    public ItemStack removeStackFromSlot(int index) {
        if (index == PowerCellContainer.SLOT_CARD) {
            handleCardRemoval();
        }
        return inventoryHelper.removeStackFromSlot(index);
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        if (index == PowerCellContainer.SLOT_CARD && inventoryHelper.containsItem(index) && count >= inventoryHelper.getStackInSlot(index).getCount()) {
            handleCardRemoval();
        }
        return inventoryHelper.decrStackSize(index, count);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        if (index == PowerCellContainer.SLOT_CARD) {
            handleCardRemoval();
        }
        inventoryHelper.setInventorySlotContents(getInventoryStackLimit(), index, stack);
        if (index == PowerCellContainer.SLOT_CARD && inventoryHelper.containsItem(index)) {
            handleCardInsertion();
        }
        else if (index == PowerCellContainer.SLOT_CARDCOPY && inventoryHelper.containsItem(index)) {
            PowerCellCardItem.setId(inventoryHelper.getStackInSlot(index), networkId);
        }
    }

    public long getTotalExtracted() {
        return totalExtracted;
    }

    public long getTotalInserted() {
        return totalInserted;
    }

    public void resetTotalExtracted() {
        this.totalExtracted = 0;
    }

    public void resetTotalInserted() {
        this.totalInserted = 0;
    }

    @Optional.Method(modid = "redstoneflux")
    @Override
    public int receiveEnergy(EnumFacing from, int maxReceive, boolean simulate) {
        return receiveEnergyFacing(from, maxReceive, simulate);
    }

    public int receiveEnergyFacing(EnumFacing from, int maxReceive, boolean simulate) {
        if (modes[from.ordinal()] != Mode.MODE_INPUT) {
            return 0;
        }
        maxReceive = Math.min(maxReceive, getRfPerTickPerSide());
        int networkId = getNetworkId();
        int received;
        if (networkId == -1) {
            received = receiveEnergyLocal(maxReceive, simulate);
        } else {
            received = receiveEnergyMulti(maxReceive, simulate);
        }
        if (!simulate) {
            totalInserted += received;
            powerIn += received;
            markDirty();
        }
        return received;
    }

    private int receiveEnergyMulti(int maxReceive, boolean simulate) {
        if (getWorld().isRemote) {
            return 0;   // Safety
        }
        PowerCellNetwork.Network network = getNetwork();
        int totEnergy = network.calculateMaximumEnergy();

        int maxInsert = Math.min(totEnergy - network.getEnergy(), maxReceive);
        if (maxInsert > 0) {
            if (!simulate) {
                maxInsert = network.receiveEnergy(maxInsert);
                PowerCellNetwork.getChannels(getWorld()).save();
            }
        }
        return isCreative() ? maxReceive : maxInsert;
    }

    private int receiveEnergyLocal(int maxReceive, boolean simulate) {
        long capacityL = (long)PowerCellConfiguration.rfPerNormalCell * getPowerFactor() / simpleFactor;
        int capacity = capacityL > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)capacityL;
        int maxInsert = Math.min(capacity - energy, maxReceive);
        if (maxInsert > 0) {
            if (!simulate) {
                energy += maxInsert;
                markDirty();
            }
        }
        return isCreative() ? maxReceive : maxInsert;
    }

    private int extractEnergyInternal(int maxExtract, boolean simulate, int maximum) {
        int networkId = getNetworkId();
        int extracted;
        if (networkId == -1) {
            extracted = extractEnergyLocal(maxExtract, simulate, maximum);
        } else {
            extracted =  extractEnergyMulti(maxExtract, simulate, maximum);
        }
        if (!simulate) {
            totalExtracted += extracted;
            powerOut += extracted;
            markDirty();
        }
        return extracted;
    }

    private int extractEnergyMulti(int maxExtract, boolean simulate, int maximum) {
        if (getWorld().isRemote) {
            return 0; // Safety
        }
        PowerCellNetwork.Network network = getNetwork();
        if (maxExtract > maximum) {
            maxExtract = maximum;
        }
        if (!simulate) {
            maxExtract = network.extractEnergy(maxExtract);
            PowerCellNetwork.getChannels(getWorld()).save();
        }
        return maxExtract;
    }

    private int extractEnergyLocal(int maxExtract, boolean simulate, int maximum) {
        // We act as a single block
        if (maxExtract > energy) {
            maxExtract = energy;
        }
        if (maxExtract > maximum) {
            maxExtract = maximum;
        }
        if (!simulate) {
            energy -= maxExtract;
            markDirty();
        }
        return maxExtract;
    }

    @Optional.Method(modid = "redstoneflux")
    @Override
    public int getEnergyStored(EnumFacing from) {
        return getEnergyStored();
    }

    public int getEnergyStored() {
        if (world.isRemote) {
            return 0;
        }
        int networkId = getNetworkId();
        if (networkId == -1) {
            return energy;
        }
        PowerCellNetwork.Network network = getNetwork();
        return network.getEnergy();
    }

    @Optional.Method(modid = "redstoneflux")
    @Override
    public int getMaxEnergyStored(EnumFacing from) {
        return getMaxEnergyStored();
    }

    public int getMaxEnergyStored() {
        if (world.isRemote) {
            return 0;
        }
        int networkId = getNetworkId();
        if (networkId == -1) {
            return PowerCellConfiguration.rfPerNormalCell * getPowerFactor() / simpleFactor;
        }
        PowerCellNetwork.Network network = getNetwork();
        return network.calculateMaximumEnergy();
    }


    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        if (index == PowerCellContainer.SLOT_CARD && stack.getItem() != PowerCellSetup.powerCellCardItem) {
            return false;
        }
        if (index == PowerCellContainer.SLOT_CARDCOPY && stack.getItem() != PowerCellSetup.powerCellCardItem) {
            return false;
        }
        return true;
    }

    @Optional.Method(modid = "redstoneflux")
    @Override
    public boolean canConnectEnergy(EnumFacing from) {
        return true;
    }

    @Override
    public InventoryHelper getInventoryHelper() {
        return inventoryHelper;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player) {
        return canPlayerAccess(player);
    }

    public void setAllOutput() {
        for (EnumFacing facing : EnumFacing.VALUES) {
            modes[facing.ordinal()] = Mode.MODE_OUTPUT;
        }
        markDirtyClient();
    }

    private void setAllInput() {
        for (EnumFacing facing : EnumFacing.VALUES) {
            modes[facing.ordinal()] = Mode.MODE_INPUT;
        }
        markDirtyClient();
    }

    private void setAllNone() {
        for (EnumFacing facing : EnumFacing.VALUES) {
            modes[facing.ordinal()] = Mode.MODE_NONE;
        }
        markDirtyClient();
    }

    @Override
    public void selectBlock(EntityPlayer player, BlockPos pos) {
        dumpNetwork(player, this);
    }

    public static void dumpNetwork(EntityPlayer player, PowerCellTileEntity powerCellTileEntity) {
        PowerCellNetwork.Network network = powerCellTileEntity.getNetwork();
        Set<GlobalCoordinate> blocks = network.getBlocks();
//        System.out.println("blocks.size() = " + blocks.size());
        blocks.forEach(b -> {
            String msg;
            World w = mcjty.lib.varia.TeleportationTools.getWorldForDimension(b.getDimension());
            if (w == null) {
                msg = "dimension missing!";
            } else {
                Block block = w.getBlockState(b.getCoordinate()).getBlock();
                if (block == PowerCellSetup.powerCellBlock) {
                    msg = "normal";
                } else if (block == PowerCellSetup.advancedPowerCellBlock) {
                    msg = "advanced";
                } else if (block == PowerCellSetup.creativePowerCellBlock) {
                    msg = "creative";
                } else {
                    msg = "not a powercell!";
                }
                TileEntity te = w.getTileEntity(b.getCoordinate());
                if (te instanceof PowerCellTileEntity) {
                    PowerCellTileEntity power = (PowerCellTileEntity) te;
                    msg += " (+:" + power.getTotalInserted() + ", -:" + power.getTotalExtracted() + ")";
                }
            }

            Logging.message(player, "Block: " + BlockPosTools.toString(b.getCoordinate()) + " (" + b.getDimension() + "): " + msg);
        });
    }

    @Override
    public TypedMap executeWithResult(String command, TypedMap args) {
        TypedMap rc = super.executeWithResult(command, args);
        if (rc != null) {
            return rc;
        }
        if (CMD_GET_INFO.equals(command)) {
            if (networkId == -1) {
                return TypedMap.builder()
                        .put(PARAM_ENERGY, getEnergy())
                        .put(PARAM_BLOCKS, 1)
                        .put(PARAM_SIMPLEBLOCKS, isSimple() ? 1 : 0)
                        .put(PARAM_ADVANCEDBLOCKS, isAdvanced() ? 1 : 0)
                        .put(PARAM_TOTAL_INSERTED, getTotalInserted())
                        .put(PARAM_TOTAL_EXTRACTED, getTotalExtracted())
                        .put(PARAM_RFPERTICK, getRfPerTickPerSide())
                        .put(PARAM_COSTFACTOR, 1.0)
                        .build();
            } else {
                PowerCellNetwork.Network network = getNetwork();
                return TypedMap.builder()
                        .put(PARAM_ENERGY, network.getEnergy())
                        .put(PARAM_BLOCKS, network.getBlockCount())
                        .put(PARAM_SIMPLEBLOCKS, network.getSimpleBlockCount())
                        .put(PARAM_ADVANCEDBLOCKS, network.getAdvancedBlockCount())
                        .put(PARAM_TOTAL_INSERTED, getTotalInserted())
                        .put(PARAM_TOTAL_EXTRACTED, getTotalExtracted())
                        .put(PARAM_RFPERTICK, getRfPerTickPerSide())
                        .put(PARAM_COSTFACTOR, (double) getCostFactor())
                        .build();
            }
        }
        return null;
    }

    @Override
    public boolean receiveDataFromServer(String command, @Nonnull TypedMap result) {
        boolean rc = super.receiveDataFromServer(command, result);
        if (rc) {
            return true;
        }
        if (CMD_GET_INFO.equals(command)) {
            tooltipEnergy = result.get(PARAM_ENERGY);
            tooltipBlocks = result.get(PARAM_BLOCKS);
            tooltipSimpleBlocks = result.get(PARAM_SIMPLEBLOCKS);
            tooltipAdvancedBlocks = result.get(PARAM_ADVANCEDBLOCKS);
            tooltipInserted = result.get(PARAM_TOTAL_INSERTED);
            tooltipExtracted = result.get(PARAM_TOTAL_EXTRACTED);
            tooltipRfPerTick = result.get(PARAM_RFPERTICK);
            PowerCellTileEntity.tooltipCostFactor = result.get(PARAM_COSTFACTOR).floatValue();
            return true;
        }
        return false;
    }

    // Forge energy
    private IEnergyStorage[] sidedHandlers = new IEnergyStorage[6];
    private IEnergyStorage nullHandler;

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY || capability == EnergyTools.TESLA_HOLDER || (capability == EnergyTools.TESLA_CONSUMER && facing != null)) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY || capability == EnergyTools.TESLA_HOLDER || (capability == EnergyTools.TESLA_CONSUMER && facing != null)) {
            if (facing == null) {
                if (nullHandler == null) {
                    nullHandler = new NullHandler();
                }
                return (T) nullHandler;
            } else {
                if (sidedHandlers[facing.ordinal()] == null) {
                    sidedHandlers[facing.ordinal()] = new SidedHandler(facing);
                }
                return (T) sidedHandlers[facing.ordinal()];
            }
        }
        return super.getCapability(capability, facing);
    }

    @Optional.InterfaceList({
        @Optional.Interface(iface = "net.darkhax.tesla.api.ITeslaConsumer", modid = "tesla"),
        @Optional.Interface(iface = "net.darkhax.tesla.api.ITeslaHolder", modid = "tesla")
    })
    private class SidedHandler implements IEnergyStorage, ITeslaConsumer, ITeslaHolder {
        private final EnumFacing facing;

        private SidedHandler(EnumFacing facing) {
            this.facing = facing;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return PowerCellTileEntity.this.receiveEnergyFacing(facing, maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return PowerCellTileEntity.this.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return PowerCellTileEntity.this.getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Optional.Method(modid = "tesla")
        @Override
        public long getStoredPower() {
            return PowerCellTileEntity.this.getEnergyStored();
        }

        @Optional.Method(modid = "tesla")
        @Override
        public long getCapacity() {
            return PowerCellTileEntity.this.getMaxEnergyStored();
        }

        @Optional.Method(modid = "tesla")
        @Override
        public long givePower(long power, boolean simulated) {
            return PowerCellTileEntity.this.receiveEnergyFacing(facing, EnergyTools.unsignedClampToInt(power), simulated);
        }
    }

    @Optional.Interface(iface = "net.darkhax.tesla.api.ITeslaHolder", modid = "tesla")
    private class NullHandler implements IEnergyStorage, ITeslaHolder {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return PowerCellTileEntity.this.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return PowerCellTileEntity.this.getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return false;
        }

        @Optional.Method(modid = "tesla")
        @Override
        public long getStoredPower() {
            return PowerCellTileEntity.this.getEnergyStored();
        }

        @Optional.Method(modid = "tesla")
        @Override
        public long getCapacity() {
            return PowerCellTileEntity.this.getMaxEnergyStored();
        }
    }
}
