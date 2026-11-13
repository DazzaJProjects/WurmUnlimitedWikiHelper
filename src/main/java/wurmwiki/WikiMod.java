package wurmwiki;

import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.MainMenu;
import com.wurmonline.client.renderer.gui.WikiPopup;
import com.wurmonline.client.renderer.gui.WikiWindow;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.client.settings.SavePosManager;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** In-game Wiki Helper: ESC menu entry with a search window, plus "Find in Wiki" on right-click menus. */
public class WikiMod implements WurmClientMod, Initable, Configurable {

    public static final Logger LOG = Logger.getLogger("WurmWiki");
    public static final WikiDatabase DB = new WikiDatabase();
    public static final WikiHistory HISTORY = new WikiHistory(Paths.get("mods", "wikihelper", "history.txt"));
    public static final Path DATA_FILE = Paths.get("mods", "wikihelper", "pages.txt");
    /** Your own entries and corrections. Anything here replaces the imported entry with the same name. */
    public static final Path CUSTOM_FILE = Paths.get("mods", "wikihelper", "custom.txt");

    /** Log popup internals to the client log; needed if "Find in Wiki" does not appear. */
    public static volatile boolean debug = false;
    /** Only add "Find in Wiki" to menus of things the database actually knows about. */
    public static volatile boolean onlyWhenFound = true;
    public static volatile int popularCount = 10;
    public static volatile int recentCount = 5;
    /** Show the page's contents list right under the title (true) or after the item box (false). */
    public static volatile boolean contentsAtTop = true;

    private static WikiWindow window;

    @Override
    public void configure(Properties p) {
        debug = Boolean.parseBoolean(p.getProperty("debug", "false"));
        onlyWhenFound = Boolean.parseBoolean(p.getProperty("onlyWhenFound", "true"));
        WikiImages.enabled = Boolean.parseBoolean(p.getProperty("downloadImages", "true"));
        popularCount = intProp(p, "popularCount", 10);
        recentCount = intProp(p, "recentCount", 5);
        contentsAtTop = Boolean.parseBoolean(p.getProperty("contentsAtTop", "true"));
    }

    private static int intProp(Properties p, String key, int def) {
        try { return Math.max(1, Integer.parseInt(p.getProperty(key, "" + def).trim())); }
        catch (NumberFormatException e) { return def; }
    }

    @Override
    public void init() {
        reload();
        HISTORY.load();

        HookManager hm = HookManager.getInstance();

        // Build the window and put it in the ESC menu once the HUD exists.
        hm.registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "init", "(II)V",
                () -> (proxy, method, args) -> {
                    method.invoke(proxy, args);
                    try {
                        install(proxy);
                    } catch (Throwable t) {
                        LOG.log(Level.SEVERE, "Wiki: could not create the window", t);
                    }
                    return null;
                });

        // Add "Find in Wiki" to right-click menus just before they are shown.
        hm.registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "showPopupComponent",
                "(Lcom/wurmonline/client/renderer/gui/WurmPopup;)V",
                () -> (proxy, method, args) -> {
                    try {
                        WikiPopup.decorate(args[0]);
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "Wiki: could not decorate popup", t);
                    }
                    return method.invoke(proxy, args);
                });
    }

    public static int reload() {
        try {
            int n = DB.load(DATA_FILE, CUSTOM_FILE);
            LOG.info("Wiki: loaded " + n + " entries from " + DATA_FILE.toAbsolutePath());
            return n;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Wiki: could not read " + DATA_FILE.toAbsolutePath(), e);
            return 0;
        }
    }

    /** Opens the window on the entry matching the given name. */
    public static void open(String name) {
        if (window != null) window.openWith(name);
    }

    private static void install(Object hud) throws Exception {
        window = new WikiWindow();

        MainMenu mainMenu = ReflectionUtil.getPrivateField(hud,
                ReflectionUtil.getField(hud.getClass(), "mainMenu"));
        List<WurmComponent> components = ReflectionUtil.getPrivateField(hud,
                ReflectionUtil.getField(hud.getClass(), "components"));
        SavePosManager positions = ReflectionUtil.getPrivateField(hud,
                ReflectionUtil.getField(hud.getClass(), "savePosManager"));

        components.add(window);
        mainMenu.registerComponent("Wiki Helper", window);

        // Start hidden; the player opens it from the menu.
        try {
            Method hide = ReflectionUtil.getMethod(HeadsUpDisplay.class, "hideComponent",
                    new Class[]{WurmComponent.class});
            ReflectionUtil.callPrivateMethod(hud, hide, window);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Wiki: could not pre-hide window", t);
        }

        positions.registerAndRefresh(window, "wikihelper");
        LOG.info("Wiki: window registered in the main menu");
    }
}
