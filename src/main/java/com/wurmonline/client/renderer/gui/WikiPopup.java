package com.wurmonline.client.renderer.gui;

import wurmwiki.WikiDatabase;
import wurmwiki.WikiMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds a "Find in Wiki" button to right-click menus. Called from a hook just before
 * HeadsUpDisplay.showPopupComponent shows a popup.
 */
public final class WikiPopup {

    private static final Set<Object> DONE = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    private WikiPopup() { }

    public static void decorate(Object candidate) {
        if (!(candidate instanceof WurmPopup)) return;
        final WurmPopup popup = (WurmPopup) candidate;
        if (!DONE.add(popup)) return;

        String title = titleOf(popup);
        if (WikiMod.debug) WikiMod.LOG.info("Wiki: popup title resolved as '" + title + "'");
        if (title == null || title.trim().isEmpty()) return;

        WikiDatabase.Page hit = WikiMod.DB.find(title);
        if (hit == null && WikiMod.onlyWhenFound) return;

        final String name = hit != null ? hit.name : WikiDatabase.normalise(title);
        popup.addSeparator();
        popup.addButton(popup.new WPopupLiveButton("Find in Wiki") {
            @Override
            protected void handleLeftClick() {
                WikiMod.open(name);
            }
        });
    }

    /**
     * The popup header is the name of the thing that was clicked. WurmPopup's accessors are
     * not documented, so look for a title-like getter or field. With debug=true everything
     * String-like on the popup is written to the client log to make this easy to fix.
     */
    private static String titleOf(WurmPopup popup) {
        if (WikiMod.debug) dump(popup);

        String[] getters = {"getTitle", "getHeader", "getHeading", "getLabel", "getText"};
        for (String name : getters) {
            try {
                Method m = popup.getClass().getMethod(name);
                if (m.getReturnType() == String.class) {
                    String v = (String) m.invoke(popup);
                    if (v != null && !v.trim().isEmpty()) return v;
                }
            } catch (Throwable ignored) { }
        }

        for (Class<?> c = popup.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName().toLowerCase();
                if (!(n.contains("title") || n.contains("header") || n.contains("heading"))) continue;
                try {
                    f.setAccessible(true);
                    String v = (String) f.get(popup);
                    if (v != null && !v.trim().isEmpty()) return v;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static void dump(WurmPopup popup) {
        try {
            StringBuilder sb = new StringBuilder("Wiki: popup " + popup.getClass().getName());
            for (Class<?> c = popup.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    sb.append("\n   field ").append(c.getSimpleName()).append('.').append(f.getName())
                            .append(" = ").append(f.get(popup));
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() == String.class && m.getParameterTypes().length == 0)
                        sb.append("\n   method ").append(c.getSimpleName()).append('.').append(m.getName()).append("()");
                }
            }
            WikiMod.LOG.info(sb.toString());
        } catch (Throwable t) {
            WikiMod.LOG.warning("Wiki: popup dump failed: " + t);
        }
    }
}
