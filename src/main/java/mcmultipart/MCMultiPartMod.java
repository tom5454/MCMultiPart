package mcmultipart;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import mcmultipart.block.BlockMultipart;
import mcmultipart.block.TileCoverable;
import mcmultipart.block.TileMultipart;
import mcmultipart.network.MultipartNetworkHandler;

@Mod(modid = MCMultiPartMod.MODID, name = MCMultiPartMod.NAME)
public class MCMultiPartMod {

    public static final String MODID = "mcmultipart", NAME = "MCMultiPart";

    @SidedProxy(serverSide = MODID + ".MCMultiPartCommonProxy", clientSide = MODID + ".client.MCMultiPartClientProxy")
    public static MCMultiPartCommonProxy proxy;

    public static BlockMultipart multipart;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {

        GameRegistry.registerBlock(multipart = new BlockMultipart(), null, "multipart");
        GameRegistry.registerTileEntity(TileMultipart.class, "mcmultipart:multipart");
        GameRegistry.registerTileEntity(TileCoverable.class, "mcmultipart:coverable");

        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {

        MultipartNetworkHandler.init();

        proxy.init();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {

    }

}