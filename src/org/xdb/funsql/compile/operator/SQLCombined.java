package org.xdb.funsql.compile.operator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import org.xdb.Config;
import org.xdb.error.Error;
import org.xdb.funsql.codegen.ReReNameExpressionVisitor;
import org.xdb.funsql.codegen.ReReNamePredicateVisitor;
import org.xdb.funsql.compile.CompilePlan;
import org.xdb.funsql.compile.analyze.expression.RenameExpressionCombineVisitor;
import org.xdb.funsql.compile.analyze.predicate.RenamePredicateCombineVisitor;
import org.xdb.funsql.compile.expression.AbstractExpression;
import org.xdb.funsql.compile.predicate.AbstractPredicate;
import org.xdb.funsql.compile.tokens.AbstractToken;
import org.xdb.funsql.compile.tokens.TokenAttribute;
import org.xdb.funsql.compile.tokens.TokenIdentifier;
import org.xdb.utils.Identifier;
import org.xdb.utils.SetUtils;
import org.xdb.utils.StringTemplate;
import org.xdb.utils.TokenPair;

import com.oy.shared.lm.graph.Graph;
import com.oy.shared.lm.graph.GraphNode;

/**
 * Operator that combines a SQL Join and a SQL Unary into one 
 * SQLCombined operator
 * 
 * @author A.C.mueller
 * 
 */
public class SQLCombined extends AbstractJoinOperator {
	/**
	 * 
	 */
	private static final long serialVersionUID = 5546062926624039185L;

	private final StringTemplate whereTemplate = new StringTemplate(
			" WHERE <WHERE> ");
	private final StringTemplate groupTemplate = new StringTemplate(
			" GROUP BY <GROUP> ");
	private final StringTemplate havingTemplate = new StringTemplate(
			" HAVING <HAVING>");

	private SQLJoin copied;
	private Error error;

	// select clause
	private Vector<AbstractExpression> selectExpressions = new Vector<AbstractExpression>();
	private Vector<TokenIdentifier> selectAliases = new Vector<TokenIdentifier>();
	
	// where clause
	private Vector<AbstractPredicate> wherePreds = new Vector<AbstractPredicate>();

	// having clause
	private AbstractPredicate havingPred;

	// group by clause
	private Vector<AbstractExpression> groupExpressions = new Vector<AbstractExpression>();

	// constructor
	public SQLCombined(CompilePlan cplan, SQLJoin toCopy) {
		super(toCopy);
		this.jointokens = toCopy.jointokens;
		this.type = EnumOperator.SQL_COMBINED;
		this.copied = toCopy;
		
		// remove selection as children 
		int childIdx = 0;
		for(AbstractCompileOperator child: this.children){
			if(child.getType().isSelection()){
				GenericSelection gs = (GenericSelection)child;
				this.children.set(childIdx, gs.getChild());
				this.wherePreds.add(gs.getPredicate());
				cplan.replaceOperator(gs.getOperatorId(), gs.getChild());
			}
			childIdx++;
		}
	}

	// copy-constructor
	public SQLCombined(SQLCombined toCopy) {
		super(toCopy);
	}

	/**
	 * Merges unary operator into SQLCombined
	 * @param sqlU
	 */
	public void mergeSQLUnaryParent(SQLUnary sqlU) {
		// Scenario SQL Join is child and Unary is parent
		this.selectExpressions.addAll(sqlU.getSelectExpressions());
		this.selectAliases.addAll(sqlU.getSelectAliases());
		
		if(sqlU.getWherePred()!=null)
			this.wherePreds.add(sqlU.getWherePred());
		
		this.havingPred = sqlU.getHavingPred();
		this.groupExpressions.addAll(sqlU.getGroupExpressions());

		// rename expressions
		this.renameExpression(this.selectExpressions);
		this.renameExpression(this.groupExpressions);
		for(AbstractPredicate wherePred: this.wherePreds){
			this.renamePredicate(wherePred);
		}
		this.renamePredicate(this.havingPred);

		// build result description
		this.results = sqlU.results;
		
		// connect with parents
		if (sqlU.getParents().size() == 0) { // Case if root
			this.clearParents();
		}
		else{
			int idx = -1;
			for (AbstractCompileOperator absOp : sqlU.parents) {
				idx = absOp.getChildren().indexOf(sqlU);
				if (idx == -1)
					continue;
				absOp.setChild(idx, this);
			}
			this.parents = sqlU.parents;
		}

		
		// connect with children
		int idx = -1;
		for (AbstractCompileOperator absOp : this.getChildren()) {
			idx = absOp.getParents().indexOf(copied);
			if (idx == -1)
				continue;
			absOp.setParent(idx, this);
		}

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

			footer.append(AbstractToken.NEWLINE);
			footer.append("Join conditions:");
			footer.append(this.jointokens);

			if (node.getInfo().getFooter() != null) {
				footer.append(AbstractToken.NEWLINE);
				footer.append(node.getInfo().getFooter());
			}
			node.getInfo().setFooter(footer.toString());
		}

		return err;
	}

	/**
	 * Eliminates the table name out of a vector of expressions
	 * 
	 * @param absExpressions
	 */
	private void renameExpression(Vector<AbstractExpression> absExpressions) {
		RenameExpressionCombineVisitor reCv;
		for (AbstractExpression absE : absExpressions) {
			reCv = new RenameExpressionCombineVisitor(absE);
			reCv.visit();

		}
	}

	/**
	 * Eleminates the table name from a given predicate
	 * 
	 * @param absExpressions
	 */
	private void renamePredicate(AbstractPredicate absP) {
		if (absP == null)
			return;
		RenamePredicateCombineVisitor rpCV = new RenamePredicateCombineVisitor(
				absP);
		rpCV.visit();
	}

	@Override
	public String toSqlString() {
		HashMap<String, String> vars = new HashMap<String, String>();

		String results = "";

		final Vector<String> selExprVec = new Vector<String>(
				this.selectExpressions.size());
		final Vector<String> selAliasVec = new Vector<String>(
				this.selectAliases.size());
		int i = 0;
		while (i < this.selectExpressions.size()) {
			selExprVec.add(this.selectExpressions.get(i).toSqlString());
			selAliasVec.add(this.selectAliases.get(i).toSqlString());
			i++;
		}

		results = SetUtils.buildAliasString(selExprVec, selAliasVec);

		// build up HashMap with table name and join attributes

		String templateString = "";
		vars.put("RESULT", results);
		
		// put join parameters into map
		int idx = 0;
		TokenPair tokenPair;

		for (int n = this.getJointokens().size() - 1; n >= 0; n--) {
			tokenPair = this.getJointokens().get(n);

			if (idx > 0) {
				templateString = templateString + " INNER JOIN <<OP"
						+ (idx + 1) + ">> AS <OP" + (idx + 1) + "> ON <JATT"
						+ (idx) + "> = <JATT" + (idx + 1) + ">";
			} else {
				templateString = "SELECT <RESULT> FROM <<OP0>> AS <OP0>  INNER JOIN <<OP1>> AS <OP1> ON <JATT0> = <JATT1>";
			}
			vars.put("OP" + idx, tokenPair.getLeftTableName());
			vars.put("JATT" + idx, tokenPair.getLeftTokenAttribute().toString());
			idx++;
			vars.put("OP" + idx, tokenPair.getRightTableName());
			vars.put("JATT" + idx, tokenPair.getRightTokenAttribute()
					.toString());
			idx++;
		}

		// append where
		templateString = templateString + getWhereClause();
		// append having
		templateString = templateString + getHavingClause();
		// append group by
		templateString = templateString + getGroupByClause();

		StringTemplate sqlTemplate = new StringTemplate(templateString);

		return sqlTemplate.toString(vars);

	}

	@Override
	public void renameTableOfAttributes(String oldChildId, String newChildId) {
		// nothing todo in here

	}

	@Override
	public void renameForPushDown(Collection<TokenAttribute> selAtts, int child) {
		// nothing todo in here

	}

	@Override
	public boolean isLeaf() {
		return (this.children.size() == 0);
	}

	public String getHavingClause() {
		final HashMap<String, String> vars = new HashMap<String, String>();
		if (this.havingPred != null) {
			vars.put("HAVING", this.havingPred.toSqlString());
			return havingTemplate.toString(vars);
		}
		return "";
	}

	public String getWhereClause() {
		final HashMap<String, String> vars = new HashMap<String, String>();
		if (this.wherePreds.size()>0) {
			StringBuilder sqlWhere = new StringBuilder();
			int whereIdx = 0;
			for(AbstractPredicate wherePred: this.wherePreds){
				sqlWhere.append(wherePred.toSqlString());
				whereIdx++;
				
				if(whereIdx!=this.wherePreds.size()){
					sqlWhere.append(AbstractToken.BLANK);
					sqlWhere.append(AbstractToken.AND);
					sqlWhere.append(AbstractToken.BLANK);
				}
			}
			vars.put("WHERE", sqlWhere.toString());
			return whereTemplate.toString(vars);
		}
		return "";
	}

	public String getGroupByClause() {
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
	public boolean renameAttributes(Map<String, String> renamedAttributes,
			Vector<String> renamedOps) {
		boolean renamed = super.renameAttributes(renamedAttributes, renamedOps);
		Error e = new Error();
		for (AbstractExpression expr : this.groupExpressions) {
			ReReNameExpressionVisitor renameVisitor = new ReReNameExpressionVisitor(
					expr, renamedAttributes);
			e = renameVisitor.visit();
		}

		for (AbstractExpression expr : this.selectExpressions) {
			ReReNameExpressionVisitor renameVisitor = new ReReNameExpressionVisitor(
					expr, renamedAttributes);
			e = renameVisitor.visit();
		}

		// rename predicates based on already renamed attributes
		ReReNamePredicateVisitor rPv;
		for(AbstractPredicate wherePred: this.wherePreds){
			rPv = new ReReNamePredicateVisitor(wherePred,
					renamedAttributes);
			e = rPv.visit();
		}

		if (this.havingPred != null) {
			rPv = new ReReNamePredicateVisitor(this.havingPred,
					renamedAttributes);
			e = rPv.visit();
		}
		this.setError(e);
		return renamed;
	}

	public Error getError() {
		return error;
	}

	private void setError(Error error) {
		this.error = error;
	}

	@Override
	public SQLCombined clone() throws CloneNotSupportedException {
		return (SQLCombined) super.clone();
	}

}