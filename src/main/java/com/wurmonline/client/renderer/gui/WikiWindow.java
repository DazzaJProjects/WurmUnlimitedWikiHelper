package com.wurmonline.client.renderer.gui;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import wurmwiki.WikiDatabase;
import wurmwiki.WikiImages;
import wurmwiki.WikiMod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;

/**
 * The Wiki Helper window: tabs on top, the page or list in the middle, search box at the bottom.
 * Lives in the game's own package so it can use package-private GUI members.
 */
public class WikiWindow extends WWindow implements InputFieldListener, WikiView.Listener, WikiTabBar.Listener {

    private static final int TAB_SEARCH = 0, TAB_POPULAR = 1, TAB_RECENT = 2;
    private static final int MAX_LIST = 40;

    private final WurmBorderPanel root;
    private final WikiTabBar tabs;
    private WikiView currentView;                      // what is on screen now (for contents jumps)
    private WurmScrollPanel currentScroll;
    private final WurmBorderPanel searchArea;          // search box on top, results below (Search tab only)
    private boolean searchShown;
    private WurmInputField searchField;

    private int tab = TAB_SEARCH;
    private WikiDatabase.Page current;                 // page being shown, or null when a list is shown
    private final Deque<String> back = new ArrayDeque<String>();
    private String query = "";
    private int serial;

    // Clicks are handled on the next tick so the content is never rebuilt in the middle of a click.
    private String pendingLink;
    private int pendingTab = -2;

    public WikiWindow() {
        super("WikiHelper");
        setTitle("Wiki Helper");
        width = 540;
        height = 540;
        resizable = true;

        root = new WurmBorderPanel("wiki.root");
        tabs = new WikiTabBar(this);
        root.setComponent(tabs, WurmBorderPanel.NORTH);

        // The search box lives in its own panel that is never rebuilt, so it keeps keyboard focus while you type.
        searchArea = new WurmBorderPanel("wiki.searcharea");
        searchField = createSearchField();
        if (searchField != null) {
            WurmBorderPanel holder = new WurmBorderPanel("wiki.search");
            holder.setComponent(searchField, WurmBorderPanel.CENTER);
            holder.setSize(300, 30);
            holder.sizeFlags = WurmBorderPanel.FIXED_HEIGHT;

            WikiHeaderBar header = new WikiHeaderBar("Search");
            WurmBorderPanel top = new WurmBorderPanel("wiki.searchtop");
            top.setComponent(header, WurmBorderPanel.NORTH);
            top.setComponent(holder, WurmBorderPanel.CENTER);
            top.setSize(300, header.barHeight() + 30);
            top.sizeFlags = WurmBorderPanel.FIXED_HEIGHT;
            searchArea.setComponent(top, WurmBorderPanel.NORTH);
        }

        setComponent(root);
        render();
    }

    // ---------------------------------------------------------------- search box

    /**
     * WurmInputField's constructor is not documented, so pick the simplest one that takes
     * a listener and fill the remaining arguments with sensible defaults.
     */
    private WurmInputField createSearchField() {
        try {
            Constructor<?> best = null;
            StringBuilder seen = new StringBuilder();
            for (Constructor<?> c : WurmInputField.class.getDeclaredConstructors()) {
                seen.append("\n   ").append(c);
                boolean listener = false, usable = true;
                for (Class<?> t : c.getParameterTypes()) {
                    if (t == InputFieldListener.class) listener = true;
                    else if (t != String.class && t != int.class && t != boolean.class) usable = false;
                }
                if (usable && listener && (best == null
                        || c.getParameterTypes().length < best.getParameterTypes().length)) best = c;
            }
            if (best == null) {
                WikiMod.LOG.severe("Wiki: no usable WurmInputField constructor. Found:" + seen);
                return null;
            }
            Class<?>[] types = best.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean idGiven = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == InputFieldListener.class) args[i] = this;
                else if (types[i] == String.class) { args[i] = idGiven ? "" : "wiki.search.field"; idGiven = true; }
                else if (types[i] == int.class) args[i] = 256;
                else args[i] = Boolean.FALSE;
            }
            best.setAccessible(true);
            WurmInputField field = (WurmInputField) best.newInstance(args);
            field.setMaxLines(1);
            field.setMaxInput(100);
            return field;
        } catch (Throwable t) {
            WikiMod.LOG.log(Level.SEVERE, "Wiki: could not create the search box", t);
            return null;
        }
    }

    @Override public void handleInput(String value) { enter(); }

    @Override public void handleInputChanged(WurmInputField field, String value) { typed(value); }

    @Override public void handleEscape(WurmInputField field) { }

    private void setSearchText(String text) {
        if (searchField == null) return;
        try {
            for (Class<?> c = searchField.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("setText", String.class);
                    m.setAccessible(true);
                    m.invoke(searchField, text);
                    return;
                } catch (NoSuchMethodException ignored) { }
            }
            WikiMod.LOG.warning("Wiki: WurmInputField has no setText; search box not pre-filled");
        } catch (Throwable t) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not fill the search box", t);
        }
    }

    // ---------------------------------------------------------------- navigation

    /** Typing: an exact name or alias shows the page straight away, anything else shows a result list. */
    private void typed(String value) {
        query = value == null ? "" : value.trim();
        tab = TAB_SEARCH;
        back.clear();
        current = query.isEmpty() ? null : WikiMod.DB.resolve(query);
        render();
    }

    /** Enter: open the best match and count it as a view. */
    private void enter() {
        if (query.isEmpty()) return;
        WikiDatabase.Page p = WikiMod.DB.resolve(query);
        if (p == null) {
            List<WikiDatabase.Page> hits = WikiMod.DB.search(query);
            if (hits.isEmpty()) return;
            p = hits.get(0);
        }
        back.clear();
        showPage(p, true);
    }

    private void showPage(WikiDatabase.Page p, boolean record) {
        current = p;
        if (record) WikiMod.HISTORY.record(p.name);
        render();
    }

    private void followLink(String target) {
        if (target.startsWith("#")) { jump(target); return; }
        WikiDatabase.Page p = WikiMod.DB.resolve(target);
        if (p == null) return;
        if (current != null && current != p) back.push(current.name);
        showPage(p, true);
    }

    /** Contents list and [top] links: scroll the open page to a section, or back to the top. */
    private void jump(String target) {
        if (currentView == null || currentScroll == null) return;
        int y = 0;
        if (target.startsWith("#sec:")) {
            try { y = Math.max(0, currentView.sectionY(Integer.parseInt(target.substring(5))) - 6); }
            catch (NumberFormatException e) { return; }
        }
        try {
            int viewport = ((FlexComponent) currentScroll.offs).height;
            currentScroll.scrollDownTo(Math.max(0, Math.min(y, currentView.height - viewport)));
        } catch (Throwable t) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not scroll the page", t);
        }
    }

    private void goBack() {
        if (back.isEmpty()) {
            current = null;
            render();
            return;
        }
        WikiDatabase.Page p = WikiMod.DB.resolve(back.pop());
        if (p == null) { current = null; render(); } else showPage(p, false);
    }

    private void selectTab(int t) {
        tab = t;
        current = null;
        back.clear();
        render();
    }

    @Override public void openPage(String target) { pendingLink = target; }

    @Override public void tabClicked(int t) { pendingTab = t; }

    @Override
    public void gameTick() {
        super.gameTick();
        if (pendingTab != -2) {
            int t = pendingTab;
            pendingTab = -2;
            if (t == -1) goBack(); else selectTab(t);
        }
        if (pendingLink != null) {
            String t = pendingLink;
            pendingLink = null;
            followLink(t);
        }
    }

    // ---------------------------------------------------------------- showing / "Find in Wiki"

    /** Used by right-click "Find in Wiki": go straight to that entry and bring the window up. */
    public void openWith(String name) {
        WikiDatabase.Page p = WikiMod.DB.resolve(name);
        if (p == null) p = WikiMod.DB.find(name);
        tab = TAB_SEARCH;
        back.clear();
        setSearchText(p != null ? p.name : name);
        query = p != null ? p.name : (name == null ? "" : name.trim());
        if (p != null) showPage(p, true);
        else { current = null; render(); }
        try {
            HeadsUpDisplay h = hud;
            if (h == null) return;
            Method enabled = ReflectionUtil.getMethod(HeadsUpDisplay.class, "isComponentEnabled",
                    new Class[]{WurmComponent.class});
            Boolean on = ReflectionUtil.callPrivateMethod(h, enabled, this);
            if (on == null || !on) h.toggleComponent(this);
        } catch (Throwable t) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not show the window", t);
        }
    }

    @Override
    void closePressed() {
        hud.hideComponent(this);
    }

    // ---------------------------------------------------------------- content

    private void render() {
        if (root == null || tabs == null) return;
        try {
            tabs.setActive(tab);
            tabs.setBackEnabled(current != null);
            List<WikiView.Block> blocks = current != null ? pageBlocks(current) : listBlocks();

            WikiView view = new WikiView(blocks, this);
            WurmArrayPanel<FlexComponent> column =
                    new WurmArrayPanel<>("wiki.col." + (serial++), WurmArrayPanel.DIR_VERTICAL);
            column.addComponent(view);
            WurmScrollPanel scroll = new WurmScrollPanel("wiki.scroll." + serial, column, false, true);
            currentView = view;
            currentScroll = scroll;
            if (tab == TAB_SEARCH && searchField != null) {
                searchArea.setComponent(scroll, WurmBorderPanel.CENTER);   // only the results are replaced
                if (!searchShown) { root.setComponent(searchArea, WurmBorderPanel.CENTER); searchShown = true; }
            } else {
                root.setComponent(scroll, WurmBorderPanel.CENTER);
                searchShown = false;
            }
            root.layout();
        } catch (Throwable t) {
            WikiMod.LOG.log(Level.WARNING, "Wiki: could not draw the window", t);
        }
    }

    private List<WikiView.Block> pageBlocks(WikiDatabase.Page p) {
        WikiImages.request(p);
        return WikiPageBuilder.build(p, WikiMod.contentsAtTop);
    }

    private List<WikiView.Block> listBlocks() {
        List<WikiView.Block> b = new ArrayList<WikiView.Block>();
        if (WikiMod.DB.size() == 0) {
            b.add(WikiView.hint("No wiki data loaded."));
            b.add(WikiView.hint("Expected: " + WikiMod.DATA_FILE));
            return b;
        }
        if (searchField == null) {
            b.add(WikiView.hint("The search box could not be created. See the client log for lines starting with 'Wiki:'."));
        }
        switch (tab) {
            case TAB_POPULAR: {
                b.add(WikiView.header("Most searched"));
                b.add(WikiView.gap(8));
                int added = 0;
                for (String name : WikiMod.HISTORY.popular(WikiMod.popularCount)) {
                    WikiDatabase.Page p = WikiMod.DB.resolve(name);
                    if (p == null) continue;
                    int n = WikiMod.HISTORY.count(name);
                    b.add(WikiView.row(p.name, n + (n == 1 ? " view" : " views"), p.name));
                    added++;
                }
                if (added == 0) b.add(WikiView.hint("Nothing here yet. Entries you open will show up here."));
                break;
            }
            case TAB_RECENT: {
                b.add(WikiView.header("Recently viewed"));
                b.add(WikiView.gap(8));
                int added = 0;
                for (String name : WikiMod.HISTORY.recent(WikiMod.recentCount)) {
                    WikiDatabase.Page p = WikiMod.DB.resolve(name);
                    if (p == null) continue;
                    b.add(WikiView.row(p.name, "", p.name));
                    added++;
                }
                if (added == 0) b.add(WikiView.hint("Nothing here yet. Entries you open will show up here."));
                break;
            }
            default: {
                if (query.isEmpty()) {
                    b.add(WikiView.hint("Type in the search box above to search " + WikiMod.DB.size()
                            + " entries by item name or by ingredient."));
                    b.add(WikiView.hint("Try: bsb, large nails, or iron ribbon."));
                    if (WikiMod.DB.size() <= MAX_LIST) {
                        b.add(WikiView.gap(6));
                        for (WikiDatabase.Page p : WikiMod.DB.all()) b.add(WikiView.row(p.name, "", p.name));
                    }
                } else {
                    List<WikiDatabase.Page> hits = WikiMod.DB.search(query);
                    b.add(WikiView.header(hits.size() + (hits.size() == 1 ? " result" : " results")));
                    b.add(WikiView.gap(8));
                    if (hits.isEmpty()) b.add(WikiView.hint("Nothing found for '" + query + "'."));
                    int n = 0;
                    for (WikiDatabase.Page p : hits) {
                        if (n++ >= MAX_LIST) break;
                        b.add(WikiView.row(p.name, "", p.name));
                    }
                    if (hits.size() > MAX_LIST)
                        b.add(WikiView.hint("... and " + (hits.size() - MAX_LIST) + " more. Type more to narrow it down."));
                }
            }
        }
        return b;
    }
}
