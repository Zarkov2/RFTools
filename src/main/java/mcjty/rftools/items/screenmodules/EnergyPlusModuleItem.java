package mcjty.rftools.items.screenmodules;

import mcjty.lib.varia.BlockTools;
import mcjty.lib.varia.EnergyTools;
import mcjty.lib.varia.Logging;
import mcjty.rftools.api.screens.IModuleGuiBuilder;
import mcjty.rftools.api.screens.IModuleProvider;
import mcjty.rftools.blocks.screens.ScreenConfiguration;
import mcjty.rftools.blocks.screens.modules.EnergyPlusBarScreenModule;
import mcjty.rftools.blocks.screens.modulesclient.EnergyPlusBarClientScreenModule;
import mcjty.rftools.items.GenericRFToolsItem;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

public class EnergyPlusModuleItem extends GenericRFToolsItem implements IModuleProvider {

    public EnergyPlusModuleItem() {
        super("energyplus_module");
        setMaxStackSize(1);
    }

    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return 1;
    }

    @Override
    public Class<EnergyPlusBarScreenModule> getServerScreenModule() {
        return EnergyPlusBarScreenModule.class;
    }

    @Override
    public Class<EnergyPlusBarClientScreenModule> getClientScreenModule() {
        return EnergyPlusBarClientScreenModule.class;
    }

    @Override
    public String getName() {
        return "RF";
    }

    @Override
    public void createGui(IModuleGuiBuilder guiBuilder) {
        guiBuilder
                .label("Label:").text("text", "Label text").color("color", "Color for the label").nl()
                .label("RF+:").color("rfcolor", "Color for the RF text").label("RF-:").color("rfcolor_neg", "Color for the negative", "RF/tick ratio").nl()
                .toggleNegative("hidebar", "Bar", "Toggle visibility of the", "energy bar").mode("RF").format("format").nl()
                .choices("align", "Label alignment", "Left", "Center", "Right").nl()
                .label("Block:").block("monitor").nl();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack itemStack, World player, List<String> list, ITooltipFlag whatIsThis) {
        super.addInformation(itemStack, player, list, whatIsThis);
        list.add(TextFormatting.GREEN + "Uses " + ScreenConfiguration.ENERGYPLUS_RFPERTICK + " RF/tick");
        boolean hasTarget = false;
        NBTTagCompound tagCompound = itemStack.getTagCompound();
        if (tagCompound != null) {
            list.add(TextFormatting.YELLOW + "Label: " + tagCompound.getString("text"));
            if (tagCompound.hasKey("monitorx")) {
                int dim;
                if (tagCompound.hasKey("monitordim")) {
                    dim = tagCompound.getInteger("monitordim");
                } else {
                    // Compatibility reasons
                    dim = tagCompound.getInteger("dim");
                }
                int monitorx = tagCompound.getInteger("monitorx");
                int monitory = tagCompound.getInteger("monitory");
                int monitorz = tagCompound.getInteger("monitorz");
                String monitorname = tagCompound.getString("monitorname");
                list.add(TextFormatting.YELLOW + "Monitoring: " + monitorname + " (at " + monitorx + "," + monitory + "," + monitorz + ")");
                list.add(TextFormatting.YELLOW + "Dimension: " + dim);
                hasTarget = true;
            }
        }
        if (!hasTarget) {
            list.add(TextFormatting.YELLOW + "Sneak right-click on a machine to set the");
            list.add(TextFormatting.YELLOW + "target for this energy module");
        }
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);
        TileEntity te = world.getTileEntity(pos);
        NBTTagCompound tagCompound = stack.getTagCompound();
        if (tagCompound == null) {
            tagCompound = new NBTTagCompound();
        }
        if (EnergyTools.isEnergyTE(te, facing)) {
            tagCompound.setInteger("monitordim", world.provider.getDimension());
            tagCompound.setInteger("monitorx", pos.getX());
            tagCompound.setInteger("monitory", pos.getY());
            tagCompound.setInteger("monitorz", pos.getZ());
            IBlockState state = player.getEntityWorld().getBlockState(pos);
            Block block = state.getBlock();
            String name = "<invalid>";
            if (block != null && !block.isAir(state, world, pos)) {
                name = BlockTools.getReadableName(world, pos);
            }
            tagCompound.setString("monitorname", name);
            if (world.isRemote) {
                Logging.message(player, "Energy module is set to block '" + name + "'");
            }
        } else {
            tagCompound.removeTag("monitordim");
            tagCompound.removeTag("monitorx");
            tagCompound.removeTag("monitory");
            tagCompound.removeTag("monitorz");
            tagCompound.removeTag("monitorname");
            if (world.isRemote) {
                Logging.message(player, "Energy module is cleared");
            }
        }
        stack.setTagCompound(tagCompound);
        return EnumActionResult.SUCCESS;
    }
}