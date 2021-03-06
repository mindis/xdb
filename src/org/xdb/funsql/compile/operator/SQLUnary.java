package org.xdb.funsql.compile.operator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import org.xdb.Config;
import org.xdb.error.Error;
import org.xdb.funsql.codegen.ReReNameExpressionVisitor;
import org.xdb.funsql.codegen.ReReNamePredicateVisitor;
import org.xdb.funsql.compile.expression.AbstractExpression;
import org.xdb.funsql.compile.expression.SimpleExpression;
import org.xdb.funsql.compile.predicate.AbstractPredicate;
import org.xdb.funsql.compile.tokens.AbstractToken;
import org.xdb.funsql.compile.tokens.TokenAttribute;
import org.xdb.funsql.compile.tokens.TokenIdentifier;
import org.xdb.utils.Identifier;
import org.xdb.utils.SetUtils;
import org.xdb.utils.StringTemplate;

import com.oy.shared.lm.graph.Graph;
import com.oy.shared.lm.graph.GraphNode;

/**
 * Coarse-grained Operator which is used during optimization to combine several
 * fine-grained operators => Generate less SQL statements for one plan!
 * 
 * @author cbinnig
 * 
 */
// TODO: clean up mess with select expressions and aggregation expressions
// somehow
public class SQLUnary extends AbstractUnaryOperator {

	private static final long serialVersionUID = 2611698550463734434L;

	// templates for SQL generation
	private final StringTemplate selectTemplate = new StringTemplate(
			"SELECT <SELECT> ");

	private final StringTemplate fromTemplate = new StringTemplate(
			"FROM <<OP1>> AS <OP1> ");
	private final StringTemplate whereTemplate = new StringTemplate(
			" WHERE <WHERE> ");
	private final StringTemplate groupTemplate = new StringTemplate(
			"GROUP BY <GROUP> ");
	private final StringTemplate havingTemplate = new StringTemplate(
			" HAVING <HAVING>");

	// select clause
	private Vector<AbstractExpression> selectExpressions = new Vector<AbstractExpression>();
	private Vector<TokenIdentifier> selectAliases = new Vector<TokenIdentifier>();
	private Vector<AbstractExpression> aggExpressions = new Vector<AbstractExpression>();

	// where clause
	private AbstractPredicate wherePred;

	// having clause
	private AbstractPredicate havingPred;

	// group by clause
	private Vector<AbstractExpression> groupExpressions = new Vector<AbstractExpression>();

	// other info
	private Map<TokenIdentifier, AbstractExpression> replaceExprMap = new HashMap<TokenIdentifier, AbstractExpression>();
	private int countOps = 0;
	private boolean addedSelection = false;
	private boolean addedProjection = false;
	private boolean addedAggregation = false;
	

	public SQLUnary(AbstractCompileOperator child) {
		super(child);

		this.type = EnumOperator.SQL_UNARY;

		// initialize from child
		for (TokenAttribute att : child.getResult().getAttributes()) {
			SimpleExpression expr = new SimpleExpression(att);
			this.replaceExprMap.put(att.getName(), expr);
			this.selectAliases.add(att.getName());
			this.selectExpressions.add(expr);
		}
	}

	/**
	 * Copy Constructor
	 * 
	 * @param toCopy
	 *            Element to Copy
	 */
	public SQLUnary(SQLUnary toCopy) {
		super(toCopy);
	}
	
	// getters and setters
	public Vector<TokenIdentifier> getSelectAliases() {
		return selectAliases;
	}

	public Vector<AbstractExpression> getAggExpressions() {
		return aggExpressions;
	}

	public AbstractPredicate getWherePred() {
		return wherePred;
	}

	public AbstractPredicate getHavingPred() {
		return havingPred;
	}

	public Vector<AbstractExpression> getGroupExpressions() {
		return groupExpressions;
	}

	public void setGroupExpressions(Vector<AbstractExpression> groupExpressions) {
		this.groupExpressions = groupExpressions;
	}

	public Map<TokenIdentifier, AbstractExpression> getReplaceExprMap() {
		return replaceExprMap;
	}
	
	public Vector<AbstractExpression> getSelectExpressions() {
		if (this.selectExpressions.size() > 0){
			return selectExpressions;
		}
		else {
			Vector<AbstractExpression> selExprs = new Vector<AbstractExpression>(
					this.aggExpressions);
			selExprs.addAll(this.groupExpressions);
			return selExprs;
		}
	}

	public int countOperators() {
		return this.countOps;
	}

	// methods
	/**
	 * Adds a selection operator to the combined operator
	 * 
	 * @param op
	 */
	public boolean addSelection(GenericSelection op) {
		if (this.addedSelection) {
			return false;
		}
		this.addedSelection = true;
		this.countOps++;

		// change where info
		if (this.wherePred != null || this.aggExpressions.size() > 0) {
			AbstractPredicate havingPred2 = op.getPredicate();
			this.havingPred = havingPred2
					.replaceExpressions(this.replaceExprMap);
		} else {
			AbstractPredicate wherePred2 = op.getPredicate();
			this.wherePred = wherePred2.replaceExpressions(this.replaceExprMap);
		}

		return true;
	}

	/**
	 * Adds a projection to the combined operator
	 * 
	 * @param op
	 */
	public boolean addProjection(GenericProjection op) {
		if (this.addedProjection) {
			return false;
		}
		this.addedProjection = true;
		this.countOps++;

		// add select attributes
		int i = 0;
		this.selectExpressions.clear();
		this.selectAliases.clear();
		Map<TokenIdentifier, AbstractExpression> newReplaceMap = new HashMap<TokenIdentifier, AbstractExpression>();
		for (TokenAttribute att : op.getResult().getAttributes()) {
			this.selectAliases.add(att.getName());
			AbstractExpression newExpr = op.getExpression(i).replaceAttribtues(
					this.replaceExprMap);
			this.selectExpressions.add(newExpr);
			newReplaceMap.put(att.getName(), newExpr);
			i++;
		}

		// exchange replace map
		this.replaceExprMap = newReplaceMap;
		
		return true;
	}

	/**
	 * Adds a rename operator to the combined operator
	 * 
	 * @param op
	 */
	public boolean addRename(Rename op) {
		this.countOps++;

		// add select info
		int i = 0;
		this.selectAliases.clear();
		Map<TokenIdentifier, AbstractExpression> newReplaceMap = new HashMap<TokenIdentifier, AbstractExpression>();
		for (TokenAttribute att : op.getResult().getAttributes()) {
			TokenIdentifier oldAliasName = op.getChild().getResult()
					.getAttribute(i).getName();
			this.selectAliases.add(att.getName());
			newReplaceMap.put(att.getName(),
					this.replaceExprMap.get(oldAliasName));
			i++;
		}

		// exchange replace map
		this.replaceExprMap = newReplaceMap;

		return true;
	}

	/**
	 * Adds an aggregation to the combined operator
	 * 
	 * @param op
	 */
	public boolean addAggregation(GenericAggregation op) {

		if (this.addedAggregation) {
			return false;
		}
		this.addedAggregation = true;
		this.countOps++;

		// add aggregation info
		int i = 0;
		this.selectAliases.clear();
		this.selectExpressions.clear();
		Map<TokenIdentifier, AbstractExpression> newReplaceMap = new HashMap<TokenIdentifier, AbstractExpression>();
		while (i < op.getAggregationExpressions().size()) {
			TokenAttribute att = op.getResult().getAttribute(i);
			this.selectAliases.add(att.getName());
			AbstractExpression newExpr = op.getAggregationExpression(i)
					.replaceAttribtues(this.replaceExprMap);
			this.aggExpressions.add(newExpr);
			newReplaceMap.put(att.getName(), newExpr);
			i++;
		}

		// add grouping info
		int j = 0;
		while (j < op.getGroupExpressions().size()) {
			TokenAttribute att = op.getResult().getAttribute(i);
			this.selectAliases.add(att.getName());
			AbstractExpression newExpr = op.getGroupExpression(j)
					.replaceAttribtues(this.replaceExprMap);
			this.groupExpressions.add(newExpr);
			newReplaceMap.put(att.getName(), newExpr);
			i++;
			j++;
		}

		// exchange replace map
		this.replaceExprMap = newReplaceMap;

		return true;
	}

	@Override
	public void renameTableOfAttributes(String oldChildId, String newChildId) {
		// Nothing to do
	}

	@Override
	public void renameForPushDown(Collection<TokenAttribute> selAtts) {
		// Nothing to do
	}

	@Override
	public boolean renameAttributes(Map<String, String> renamedAttributes,
			Vector<String> renamedOps) {
		boolean renamed = super.renameAttributes(renamedAttributes, renamedOps);
		
		for (AbstractExpression expr : this.aggExpressions) {
			ReReNameExpressionVisitor renameVisitor = new ReReNameExpressionVisitor(
					expr, renamedAttributes);
			renameVisitor.visit();
		}
		for (AbstractExpression expr : this.groupExpressions) {
			ReReNameExpressionVisitor renameVisitor = new ReReNameExpressionVisitor(
					expr, renamedAttributes);
			renameVisitor.visit();
		}

		for (AbstractExpression expr : this.selectExpressions) {
			ReReNameExpressionVisitor renameVisitor = new ReReNameExpressionVisitor(
					expr, renamedAttributes);
			renameVisitor.visit();
		}

		// rename predicates based on already renamed attributes
		ReReNamePredicateVisitor rPv;
		if (this.wherePred != null) {
			rPv = new ReReNamePredicateVisitor(this.wherePred,
					renamedAttributes);
			rPv.visit();
		}

		if (this.havingPred != null) {
			rPv = new ReReNamePredicateVisitor(this.havingPred,
					renamedAttributes);
			rPv.visit();
		}

		return renamed;
	}
	
	@Override
	public String toSqlString() {
		// check for missing info
		if (this.selectAliases.size() == 0)
			return null;

		final HashMap<String, String> vars = new HashMap<String, String>();
		StringBuffer sqlStmt = new StringBuffer();

		// select clause
		final Vector<String> selExprVec = new Vector<String>(
				this.selectAliases.size());
		final Vector<String> selAliasVec = new Vector<String>(
				this.selectAliases.size());
		int i = 0;
		Vector<AbstractExpression> selExprs = this.getSelectExpressions();
		while (i < selExprs.size()) {
			selExprVec.add(selExprs.get(i).toSqlString());
			selAliasVec.add(this.selectAliases.get(i).toSqlString());
			i++;
		}
		vars.put("SELECT", SetUtils.buildAliasString(selExprVec, selAliasVec));
		sqlStmt.append(selectTemplate.toString(vars));

		// from clause
		vars.clear();
		// whether use table or other complex template
		vars.put("OP1", getChild().getOperatorId().toString());
		sqlStmt.append(fromTemplate.toString(vars));

		// where clause
		sqlStmt.append(getWhereClause());
		// having clause

		sqlStmt.append(getHavingClause());
		// group-by clause

		sqlStmt.append(getGroupByClause());
		return sqlStmt.toString();
	}

	private String getHavingClause() {
		final HashMap<String, String> vars = new HashMap<String, String>();
		if (this.havingPred != null) {
			vars.put("HAVING", this.havingPred.toSqlString());
			return havingTemplate.toString(vars);
		}
		return "";
	}

	private String getWhereClause() {
		final HashMap<String, String> vars = new HashMap<String, String>();
		if (this.wherePred != null) {
			vars.put("WHERE", this.wherePred.toSqlString());
			return whereTemplate.toString(vars);
		}
		return "";
	}

	private String getGroupByClause() {
		final HashMap<String, String> vars = new HashMap<String, String>();
		if (this.groupExpressions.size() > 0) {
			final Vector<String> groupExprVec = new Vector<String>(
					this.groupExpressions.size());
			for (AbstractExpression exp : this.groupExpressions) {
				groupExprVec.add(exp.toSqlString());
			}
			vars.put("GROUP", SetUtils.buildString(groupExprVec));
			return (groupTemplate.toString(vars));
		}
		return "";
	}

	@Override
	public Error traceOperator(Graph g, Map<Identifier, GraphNode> nodes) {
		Error err = super.traceOperator(g, nodes);
		if (err.isError())
			return err;

		GraphNode node = nodes.get(this.operatorId);
		if (Config.TRACE_COMPILE_PLAN_FOOTER) {
			StringBuffer footer = new StringBuffer();
			footer.append("Expressions:");
			footer.append(this.selectExpressions);

			if (node.getInfo().getFooter() != null) {
				footer.append(AbstractToken.NEWLINE);
				footer.append(node.getInfo().getFooter());
			}
			node.getInfo().setFooter(footer.toString());
		}
		return err;
	}

	@Override
	public SQLUnary clone() throws CloneNotSupportedException {
		return (SQLUnary) super.clone();
	}

}
