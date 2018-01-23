package com.linbit.utils;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TreePrinter
{
    private static final String MARKER_SUB = "\u251C\u2500 ";
    private static final String MARKER_SUB_LAST = "\u2514\u2500 ";
    private static final String PREFIX_VLINE = "\u2502  ";
    private static final String PREFIX_SPACE = "   ";

    public static Builder builder(String rootFormat, final Object... args)
    {
        return new Builder(String.format(rootFormat, args));
    }

    public static void print(PrintStream out, TreePrinterNode root)
    {
        print(out, root, "", true, true);
    }

    private static void print(PrintStream out, TreePrinterNode node, String prefix, boolean isRoot, boolean isLast)
    {
        String marker = isRoot ? "" : (isLast ? MARKER_SUB_LAST : MARKER_SUB);

        out.println(prefix + marker + node.getName());

        List<TreePrinterNode> children = node.getChildren();

        String childrenPrefix = prefix + (isRoot ? "" : (isLast ? PREFIX_SPACE : PREFIX_VLINE));

        for (int i = 0; i < children.size() - 1; i++)
        {
            print(out, children.get(i), childrenPrefix, false, false);
        }

        if (children.size() > 0)
        {
            print(out, children.get(children.size() - 1), childrenPrefix, false, true);
        }
    }

    public static class TreePrinterNode
    {
        private final String name;

        private final List<TreePrinterNode> children;

        public TreePrinterNode(final String name, final List<TreePrinterNode> children)
        {
            this.name = name;
            this.children = children;
        }

        public String getName()
        {
            return name;
        }

        public List<TreePrinterNode> getChildren()
        {
            return children;
        }
    }

    public static class TreePrinterNodeBuilder
    {
        private final TreePrinterNodeBuilder parent;

        private final String name;

        private List<TreePrinterNode> children;

        public TreePrinterNodeBuilder(final TreePrinterNodeBuilder parent, final String name)
        {
            this.parent = parent;
            this.name = name;

            children = new ArrayList<>();
        }

        public TreePrinterNodeBuilder getParent()
        {
            return parent;
        }

        public TreePrinterNode build()
        {
            return new TreePrinterNode(name, children);
        }

        public void addChild(TreePrinterNode node)
        {
            children.add(node);
        }
    }

    public static class Builder
    {
        private TreePrinterNodeBuilder current;

        public Builder(final String name)
        {
            this.current = new TreePrinterNodeBuilder(null, name);
        }

        public Builder leaf(final String format, final Object... args)
        {
            current.addChild(new TreePrinterNode(String.format(format, args), Collections.<TreePrinterNode>emptyList()));
            return this;
        }

        public Builder branch(final String format, final Object... args)
        {
            current = new TreePrinterNodeBuilder(current, String.format(format, args));
            return this;
        }

        public Builder endBranch()
        {
            TreePrinterNodeBuilder parent = current.getParent();

            if (parent != null)
            {
                parent.addChild(current.build());
                current = parent;
            }
            else
            {
                throw new IllegalStateException("Cannot end root branch");
            }

            return this;
        }

        public void print(PrintStream out)
        {
            TreePrinter.print(out, current.build());
        }
    }
}
