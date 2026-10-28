package com.jiusis.mistep;

import java.util.*;

/** Production traversal, including native freeforms, nested areas and native exclusions. */
public final class WorkspaceFocusTest {
    static final class Node {
        List<Node> children;
        boolean side, eligible = true;
        int mode;
        Node(int mode, boolean side) { this.mode = mode; this.side = side; }
        Node(Node... children) { this.children = Arrays.asList(children); }
    }
    static final WorkspaceFocus.Tree TREE = new WorkspaceFocus.Tree() {
        public int size(Object n) { return ((Node)n).children.size(); }
        public Object child(Object n,int i) { return ((Node)n).children.get(i); }
        public Object area(Object n) { return ((Node)n).children == null ? null : n; }
        public Object task(Object n) { return ((Node)n).children == null ? n : null; }
        public boolean eligible(Object n) { return ((Node)n).eligible; }
        public int mode(Object n) { return ((Node)n).mode; }
    };
    static int checks;
    static void expect(Object expected, Node area, Node ignored, boolean exclude, int mode) {
        checks++;
        Object result=WorkspaceFocus.next(TREE, n->((Node)n).side, area, ignored, exclude, mode);
        if (result != expected) throw new AssertionError("focus scan "+checks);
    }
    public static void main(String[] args) {
        Node home=new Node(1,false), main=new Node(1,false), side=new Node(6,true);
        Node floating=new Node(5,false), other=new Node(6,false);
        expect(main,new Node(home,main,side),null,false,0);
        expect(floating,new Node(home,main,floating,side),null,false,0);
        floating.eligible=false;
        expect(main,new Node(home,main,floating,side),null,false,0);
        floating.eligible=true;
        expect(main,new Node(home,main,floating,side),floating,false,0);
        expect(main,new Node(home,main,floating,side),null,true,5);
        expect(home,new Node(home,main,side),main,false,0);
        expect(null,new Node(side),null,false,0);
        expect(null,new Node(side,new Node(6,true)),null,false,0);
        expect(other,new Node(main,other,side),null,false,0);
        expect(main,new Node(main,new Node(side)),null,false,0);
        expect(floating,new Node(main,new Node(floating,side)),null,false,0);
        expect(main,new Node(main,new Node(floating,side)),floating,false,0);
        expect(null,new Node(home,main,side),null,true,1);
        main.eligible=false;
        expect(home,new Node(home,main,side),null,false,0);
        System.out.println("Workspace production focus traversal: "+checks+" checks passed");
    }
}
