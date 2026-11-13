package com.wurmonline.client.renderer.gui;

import wurmwiki.WikiDatabase;

import java.util.ArrayList;
import java.util.List;

/** Turns a wiki entry into the blocks the window draws: title, contents, item box, then the article. */
final class WikiPageBuilder {

    private WikiPageBuilder() { }

    static List<WikiView.Block> build(WikiDatabase.Page p, boolean contentsAtTop) {
        List<WikiView.Block> b = new ArrayList<WikiView.Block>();
        List<String> body = p.body();

        b.add(WikiView.title(p.name));

        List<WikiView.Block> contents = contents(body);
        if (contentsAtTop) b.addAll(contents);

        if (!p.examine.isEmpty()) {
            b.add(WikiView.header("Examine"));
            if (!p.image.isEmpty()) b.add(WikiView.image(p));
            b.add(WikiView.note(p.examine));
        } else if (!p.image.isEmpty()) {
            b.add(WikiView.image(p));
        }
        for (WikiDatabase.Section s : p.sections) {
            b.add(WikiView.header(s.title));
            for (String line : s.lines) b.add(WikiView.line(line));
        }

        if (!contentsAtTop) b.addAll(contents);
        article(body, b);
        return b;
    }

    /** "Contents" box listing the headings, numbered like the wiki (1, 1.1, 2 ...). Empty if there is little to jump to. */
    static List<WikiView.Block> contents(List<String> body) {
        List<WikiView.Block> toc = new ArrayList<WikiView.Block>();
        int n2 = 0, n3 = 0, idx = 0;
        for (String l : body) {
            if (l.startsWith("H2 ")) {
                n2++; n3 = 0;
                toc.add(WikiView.toc("" + n2, l.substring(3), idx++, 0));
            } else if (l.startsWith("H3 ")) {
                n3++;
                toc.add(WikiView.toc(n2 > 0 ? n2 + "." + n3 : "" + n3, l.substring(3), idx++, n2 > 0 ? 1 : 0));
            }
        }
        if (toc.size() < 2) return new ArrayList<WikiView.Block>();
        toc.add(0, WikiView.header("Contents"));
        return toc;
    }

    /** Article lines (see WikiDatabase.Page.body) to blocks. Consecutive table rows become one table. */
    static void article(List<String> body, List<WikiView.Block> out) {
        int idx = 0;
        for (int i = 0; i < body.size(); ) {
            String l = body.get(i);
            if (l.startsWith("TH ") || l.startsWith("TR ")) {
                List<String[]> rows = new ArrayList<String[]>();
                List<Boolean> header = new ArrayList<Boolean>();
                while (i < body.size() && (body.get(i).startsWith("TH ") || body.get(i).startsWith("TR "))) {
                    rows.add(body.get(i).substring(3).split("\t", -1));
                    header.add(body.get(i).startsWith("TH "));
                    i++;
                }
                out.add(WikiView.table(rows, header));
                continue;
            }
            i++;
            int sp = l.indexOf(' ');
            String tag = sp < 0 ? l : l.substring(0, sp), text = sp < 0 ? "" : l.substring(sp + 1);
            if (tag.equals("H2")) out.add(WikiView.h2(text, idx++));
            else if (tag.equals("H3")) out.add(WikiView.h3(text, idx++));
            else if (tag.equals("P")) out.add(WikiView.para(text));
            else if (tag.equals("L")) out.add(WikiView.bullet(text, 0));
            else if (tag.equals("L2")) out.add(WikiView.bullet(text, 1));
            else if (tag.equals("N")) out.add(WikiView.num(text, 0));
            else if (tag.equals("N2")) out.add(WikiView.num(text, 1));
        }
    }
}
