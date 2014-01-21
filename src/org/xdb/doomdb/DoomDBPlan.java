package org.xdb.doomdb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xdb.error.Error;
import org.xdb.utils.Dotty;
import org.xdb.utils.Identifier;

import com.oy.shared.lm.graph.Graph;
import com.oy.shared.lm.graph.GraphFactory;
import com.oy.shared.lm.graph.GraphNode;


public class DoomDBPlan implements Serializable {

	private static final long serialVersionUID = -1963805350351443744L;
	private static final String TRACE_FILE_NAME = DoomDBPlan.class.getName();
	
	private Identifier compilePlanId;
	private Identifier qtrackerPlanId;

	private Set<Identifier> nodes = new HashSet<Identifier>();
	private Map<Identifier, List<Identifier>> edges = new HashMap<Identifier, List<Identifier>>();

	public DoomDBPlan() {
	}

	public DoomDBPlan(Identifier compilePlanId, Identifier qtrackerPlanId) {
		this.compilePlanId = compilePlanId;
		this.qtrackerPlanId = qtrackerPlanId;
	}
	
	public Identifier getCompilePlanId() {
		return compilePlanId;
	}

	public Identifier getQtrackerPlanId() {
		return qtrackerPlanId;
	}

	public void addNode(Identifier node) {
		this.nodes.add(node);
	}

	public void addEdge(Identifier from, Identifier to) {
		List<Identifier> tos = null;
		if (!this.edges.containsKey(from)) {
			tos = new ArrayList<Identifier>();
			this.edges.put(from, tos);
		} else {
			tos = this.edges.get(from);
		}

		tos.add(to);
	}

	public Set<Identifier> getNodes() {
		return this.nodes;
	}

	public List<Identifier> getEdges(String from) {
		return this.edges.get(from);
	}
	
	public Error tracePlan() {
		String fileName = TRACE_FILE_NAME + this.compilePlanId.toString();
		final Error error = new Error();
		final Graph graph = GraphFactory.newGraph();

		final Map<Identifier, GraphNode> nodes = new HashMap<Identifier, GraphNode>();

		// add nodes to plan
		for (Identifier opId : this.nodes) {
			GraphNode node = graph.addNode();
			node.getInfo().setCaption(opId.toString());
			nodes.put(opId, node);
		}

		// add edges to plan
		for (Map.Entry<Identifier, List<Identifier>> entry : this.edges
				.entrySet()) {
			Identifier fromId = entry.getKey();
			GraphNode from = nodes.get(fromId);

			for (Identifier toId : entry.getValue()) {
				GraphNode to = nodes.get(toId);
				graph.addEdge(to, from);
			}
		}

		Dotty.dot2Img(graph, fileName);
		return error;
	}
}