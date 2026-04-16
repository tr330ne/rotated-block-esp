package treeone.rotatedblockesp;

import treeone.rotatedblockesp.module.RotatedBlockESP;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("TreeOne");

    @Override
    public void onInitialize() {
        LOG.info("Initializing RotatedBlockESP");
        Modules.get().add(new RotatedBlockESP());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "treeone.rotatedblockesp";
    }
}