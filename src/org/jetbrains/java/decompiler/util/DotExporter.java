package org.jetbrains.java.decompiler.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.Map.Entry;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectNode;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionEdge;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionNode;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionsGraph;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory.FastSparseSet;

public class DotExporter {
  private static final String DOTS_FOLDER = System.getProperty("DOT_EXPORT_DIR", null);
  private static final boolean DUMP_DOTS = DOTS_FOLDER != null && !DOTS_FOLDER.trim().isEmpty();
  // http://graphs.grevian.org/graph is a nice visualizer for the outputed dots.

  // Outputs a statement and as much of its information as possible into a dot formatted string.
  // Nodes represent statements, their id, their type, and their code.
  // Black arrows represent a statement's successors.
  // Dotted arrows represent a statement's exception successors.
  // Blue arrows represent a statement's predecessors. The arrow points towards the predecessor.
  // Red arrows represent the statement tree. The arrows points to the statement's children.
  // Black arrows with a diamond head represent a statement's closure, or the statement that it's enclosed in.
  // Purple arrows with a bar head represent a statement's neighbors. TODO: needs backwards as well
  // Statements with no successors or predecessors (but still contained in the tree) will be in a subgraph titled "Isolated statements".
  // Statements that aren't found will be circular, and will have a message stating so.
  // Nodes with green borders are the canonical exit of method, but these may not always be emitted.
  private static String toDotFormat(Statement stat, String name) {
    StringBuffer buffer = new StringBuffer();
    List<String> subgraph = new ArrayList<>();
    Set<Integer> visitedNodes = new HashSet<>();
    Set<Integer> exits = new HashSet<>();
    Set<Integer> referenced = new HashSet<>();

    buffer.append("digraph " + name + " {\r\n");

    List<Statement> stats = new ArrayList<>();
    stats.add(stat);
    findAllStats(stats, stat);

    // Pre process
    Map<StatEdge, String> extraData = new HashMap<>();

    for (Statement st : stats) {
      if (st.type == Statement.TYPE_IF) {
        IfStatement ifs = (IfStatement) st;

        if (ifs.getIfEdge() != null) {
          extraData.put(ifs.getIfEdge(), "If Edge");
        }

        if (ifs.getElseEdge() != null) {
          extraData.put(ifs.getElseEdge(), "If Edge");
        }
      }
    }

    for(Statement st : stats) {
      String sourceId = st.id + (st.getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");

      boolean edges = false;
      for(StatEdge edge : st.getSuccessorEdges(Statement.STATEDGE_ALL)) {
        String destId = edge.getDestination().id + (edge.getDestination().getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");

        String edgeType = getEdgeType(edge);

        // Add extra edge data
        // TODO do same for predecessors?
        for (Entry<StatEdge, String> entry : extraData.entrySet()) {
          if (edge.getSource().id.equals(entry.getKey().getSource().id) && edge.getDestination().id.equals(entry.getKey().getDestination().id)) {
            edgeType = edgeType == null ? entry.getValue() : edgeType + " (" + entry.getValue() + ")";
          }
        }

        buffer.append(sourceId + "->" + destId + (edgeType != null ? "[label=\"" + edgeType + "\"]" : "") + ";\r\n");

        if (edge.closure != null) {
          String clsId = edge.closure.id + (edge.getDestination().getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");
          buffer.append(sourceId + "->" + clsId + " [arrowhead=diamond,label=\"Closure\"];\r\n");
        }

        // TODO: why are some returns break edges instead of returns?
        if (edge.getType() == StatEdge.TYPE_FINALLYEXIT || edge.getType() == StatEdge.TYPE_BREAK) {
          exits.add(edge.getDestination().id);
        }

        referenced.add(edge.getDestination().id);

        edges = true;
      }

      for (Statement neighbour : st.getNeighbours(Statement.STATEDGE_ALL, Statement.DIRECTION_FORWARD)) {
        String destId = neighbour.id + (neighbour.getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");
        buffer.append(sourceId + "->" + destId + " [arrowhead=tee,color=purple];\r\n");
      }

      for(StatEdge edge : st.getPredecessorEdges(Statement.STATEDGE_ALL)) {
        String destId = edge.getSource().id + (edge.getSource().getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty() ? "" : "000000");

        String edgeType = getEdgeType(edge);

        buffer.append(sourceId + "->" + destId + "[color=blue" + (edgeType != null ? ",fontcolor=blue,label=\"" + edgeType + "\"" : "") + "];\r\n");

        referenced.add(edge.getSource().id);

        edges = true;
      }

      for(StatEdge edge : st.getSuccessorEdges(StatEdge.TYPE_EXCEPTION)) {
        String destId = edge.getDestination().id + (edge.getDestination().getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");

        buffer.append(sourceId + " -> " + destId + " [style=dotted];\r\n");
        referenced.add(edge.getDestination().id);

        edges = true;
      }

      // Special case if edges
      // TODO: add labels onto existing successors

      // Graph tree
      boolean foundFirst = false;
      for (Statement s : st.getStats()) {
        String destId = s.id + (s.getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");

        String label = "";

        if (s == st.getFirst()) {
          label = "First";
          foundFirst = true;
        }

        if (st.type == Statement.TYPE_IF) {
          IfStatement ifs = (IfStatement) st;
          if (s == ifs.getIfstat()) {
            label = "If stat";
          }
          if (s == ifs.getElsestat()) {
            label = "Else stat";
          }
        }

        buffer.append(sourceId + " -> " + destId + " [arrowhead=vee,color=red" + (!label.equals("") ? ",fontcolor=red,label=\"" + label + "\"" : "") + "];\r\n");
        referenced.add(s.id);
      }

      if (!foundFirst && st.getFirst() != null) {
        String destId = st.getFirst().id + (st.getFirst().getSuccessorEdges(StatEdge.TYPE_EXCEPTION).isEmpty()?"":"000000");
        buffer.append(sourceId + "->" + destId + " [arrowhead=vee,color=red,fontcolor=red,label=\"Dangling First statement!\"];\r\n");
      }

      visitedNodes.add(st.id);

      String node = sourceId + " [shape=box,label=\"" + st.id + " (" + getStatType(st) + ")\r\n" + toJava(st) + "\"" + (st == stat ? ",color=red" : "") + "];\r\n";
      if (edges || st == stat) {
        buffer.append(node);
      } else {
        subgraph.add(node);
      }
    }

    // Exits
    for (Integer exit : exits) {
      if (!visitedNodes.contains(exit)) {
        buffer.append(exit + " [color=green,label=\"" + exit + " (Canonical Return)\"];\r\n");
      }

      referenced.remove(exit);
    }

    referenced.removeAll(visitedNodes);

    // Unresolved statement references
    for (Integer integer : referenced) {
      buffer.append(integer + " [color=red,label=\"" + integer + " (Unknown statement!)\"];\r\n");
    }

    if (subgraph.size() > 0) {
      buffer.append("subgraph cluster_non_parented {\r\n\tlabel=\"Isolated statements\";\r\n");

      for (String s : subgraph) {
        buffer.append("\t"+s);
      }

      buffer.append("\t}\r\n");
    }

    buffer.append("}");

    return buffer.toString();
  }

  private static String statementHierarchy(Statement stat) {
    StringBuffer buffer = new StringBuffer();

    buffer.append("digraph G {\r\n");

    buffer.append("subgraph cluster_root {\r\n");

    buffer.append(stat.id + " [shape=box,label=\"" + stat.id + " (" + getStatType(stat) + ")\r\n" + toJava(stat) + "\"" + "];\r\n");

    recursivelyGraphStatement(buffer, stat);

    buffer.append("}\r\n");

    buffer.append("}");

    return buffer.toString();
  }

  private static void recursivelyGraphStatement(StringBuffer buffer, Statement statement) {
    buffer.append("subgraph cluster_" + statement.id + " {\r\n");
    buffer.append("label=\"" + statement.id + " (" + getStatType(statement) + ")\r\n" + toJava(statement) + "\"" + ";\r\n");

    for (Statement stat : statement.getStats()) {
      buffer.append(stat.id + " [shape=box,label=\"" + stat.id + " (" + getStatType(stat) + ")\r\n" + toJava(stat) + "\"" + "];\r\n");

      recursivelyGraphStatement(buffer, stat);
    }


    buffer.append("}\r\n");
  }

  private static String toJava(Statement statement) {
    try {
      return statement.toJava().toString();
    } catch (Exception e) {
      return "Could not get content";
    }
  }

  private static String getEdgeType(StatEdge edge) {
    switch (edge.getType()) {
      case StatEdge.TYPE_REGULAR: return null;
      case StatEdge.TYPE_EXCEPTION: return "Exception";
      case StatEdge.TYPE_BREAK: return "Break";
      case StatEdge.TYPE_CONTINUE: return "Continue";
      case StatEdge.TYPE_FINALLYEXIT: return "Finally Exit";
      default: return "Unknown Edge (composite?)";
    }
  }

  private static String getStatType(Statement st) {
    switch (st.type) {
      case 0: return "General";
      case 2: return "If";
      case 5: return "Do";
      case 6: return "Switch";
      case 7: return "Try Catch";
      case 8: return "Basic Block";
      case 10: return "Synchronized";
      case 11: return "Placeholder";
      case 12: return "Catch All";
      case 13: return "Root";
      case 14: return "Dummy Exit";
      case 15: return "Sequence";
      default: return "Unknown";
    }
  }

  private static void findAllStats(List<Statement> list, Statement root) {
    for (Statement stat : root.getStats()) {
      if (!list.contains(stat)) {
        list.add(stat);
      }

      if (stat.type == Statement.TYPE_IF) {
        IfStatement ifs = (IfStatement) stat;

        if (ifs.getIfstat() != null && !list.contains(ifs.getIfstat())) {
          list.add(ifs.getIfstat());
        }

        if (ifs.getElsestat() != null && !list.contains(ifs.getElsestat())) {
          list.add(ifs.getElsestat());
        }
      }

      findAllStats(list, stat);
    }
  }


  private static String toDotFormat(ControlFlowGraph graph, boolean showMultipleEdges) {

    StringBuffer buffer = new StringBuffer();

    buffer.append("digraph G {\r\n");

    List<BasicBlock> blocks = graph.getBlocks();
    for(int i=0;i<blocks.size();i++) {
      BasicBlock block = (BasicBlock)blocks.get(i);

      buffer.append(block.id+" [shape=box,label=\""+block.id+"\"];\r\n");


      List<BasicBlock> suc = block.getSuccs();
      if(!showMultipleEdges) {
        HashSet<BasicBlock> set = new HashSet<BasicBlock>();
        set.addAll(suc);
        suc = Collections.list(Collections.enumeration(set));
      }
      for(int j=0;j<suc.size();j++) {
        buffer.append(block.id+"->"+((BasicBlock)suc.get(j)).id+";\r\n");
      }


      suc = block.getSuccExceptions();
      if(!showMultipleEdges) {
        HashSet<BasicBlock> set = new HashSet<BasicBlock>();
        set.addAll(suc);
        suc = Collections.list(Collections.enumeration(set));
      }
      for(int j=0;j<suc.size();j++) {
        buffer.append(block.id+" -> "+((BasicBlock)suc.get(j)).id+" [style=dotted];\r\n");
      }
    }

    buffer.append("}");

    return buffer.toString();
  }

  private static String toDotFormat(VarVersionsGraph graph) {

    StringBuffer buffer = new StringBuffer();

    buffer.append("digraph G {\r\n");

    List<VarVersionNode> blocks = graph.nodes;
    for(int i=0;i<blocks.size();i++) {
      VarVersionNode block = blocks.get(i);

      buffer.append((block.var*1000+block.version)+" [shape=box,label=\""+block.var+"_"+block.version+"\"];\r\n");

      for(VarVersionEdge edge: block.succs) {
        VarVersionNode dest = edge.dest;
        buffer.append((block.var*1000+block.version)+"->"+(dest.var*1000+dest.version)+(edge.type==VarVersionEdge.EDGE_PHANTOM?" [style=dotted]":"")+";\r\n");
      }
    }

    buffer.append("}");

    return buffer.toString();
  }

  private static String toDotFormat(DirectGraph graph, Map<String, SFormsFastMapDirect> vars) {

    StringBuffer buffer = new StringBuffer();

    buffer.append("digraph G {\r\n");

    List<DirectNode> blocks = graph.nodes;
    for(int i=0;i<blocks.size();i++) {
      DirectNode block = blocks.get(i);

      StringBuilder label = new StringBuilder(block.id);
      if (vars != null && vars.containsKey(block.id)) {
        SFormsFastMapDirect map = vars.get(block.id);

        List<Entry<Integer, FastSparseSet<Integer>>> lst = map.entryList();
        if (lst != null) {
          for (Entry<Integer, FastSparseSet<Integer>> entry : lst) {
             label.append("\\n").append(entry.getKey());
            Set<Integer> set = entry.getValue().toPlainSet();
            label.append("=").append(set.toString());
          }
        }
      }

      buffer.append(directBlockIdToDot(block.id)+" [shape=box,label=\""+label+"\"];\r\n");

      for(DirectNode dest: block.succs) {
        buffer.append(directBlockIdToDot(block.id)+"->"+directBlockIdToDot(dest.id)+";\r\n");
      }
    }

    buffer.append("}");

    return buffer.toString();
  }

  private static String directBlockIdToDot(String id) {
    id = id.replaceAll("_try", "999");
    id = id.replaceAll("_tail", "888");

    id = id.replaceAll("_init", "111");
    id = id.replaceAll("_cond", "222");
    id = id.replaceAll("_inc", "333");
    return id;
  }

  private static File getFile(StructMethod mt, String suffix) {
    File root = new File(DOTS_FOLDER + mt.getClassQualifiedName());
    if (!root.isDirectory())
      root.mkdirs();
    return new File(root,
      mt.getName().replace('<', '.').replace('>', '_') +
      mt.getDescriptor().replace('/', '.') +
      '_' + suffix + ".dot");
  }

  private static File getFile(String name) {
    File root = new File(DOTS_FOLDER);
    if (!root.isDirectory())
      root.mkdirs();
    return new File(root,name + ".dot");
  }

  public static void toDotFile(DirectGraph dgraph, StructMethod mt, String suffix) {
    toDotFile(dgraph, mt, suffix, null);
  }
  public static void toDotFile(DirectGraph dgraph, StructMethod mt, String suffix, Map<String, SFormsFastMapDirect> vars) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(mt, suffix)));
      out.write(toDotFormat(dgraph, vars).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void toDotFile(Statement stat, StructMethod mt, String suffix) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(mt, suffix)));
      out.write(toDotFormat(stat, suffix).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void statementHierarchyToDot(Statement stat, StructMethod mt, String suffix) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(mt, suffix)));
      out.write(statementHierarchy(stat).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void toDotFile(Statement stat, String name) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(name)));
      out.write(toDotFormat(stat, name).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void toDotFile(VarVersionsGraph graph, StructMethod mt, String suffix) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(mt, suffix)));
      out.write(toDotFormat(graph).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void toDotFile(ControlFlowGraph graph, StructMethod mt, String suffix, boolean showMultipleEdges) {
    if (!DUMP_DOTS)
      return;
    try{
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(getFile(mt, suffix)));
      out.write(toDotFormat(graph, showMultipleEdges).getBytes());
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}