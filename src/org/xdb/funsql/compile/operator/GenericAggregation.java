package org.xdb.funsql.compile.operator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Vector;

import org.xdb.Config;
import org.xdb.error.Error;
import org.xdb.funsql.compile.expression.AbstractExpression;
import org.xdb.funsql.compile.tokens.AbstractToken;
import org.xdb.funsql.compile.tokens.TokenAttribute;
import org.xdb.funsql.compile.tokens.TokenIdentifier;
import org.xdb.utils.SetUtils;
import org.xdb.utils.StringTemplate;
import org.xdb.utils.Identifier;

import com.oy.shared.lm.graph.Graph;
import com.oy.shared.lm.graph.GraphNode;

public class GenericAggregation extends AbstractUnaryOperator {

	private static final long serialVersionUID = 3800517017256774443L;

	private Vector<AbstractExpression> groupExprs;
	private Vector<AbstractExpression> aggExprs;
	private Vector<TokenIdentifier> aliases;

	private final StringTemplate sqlTemplate = new StringTemplate(
			"SELECT <RESULT> FROM <<OP1>> AS <OP1>" + " GROUP BY <GROUP_ATTRS>");
	
	private final StringTemplate sqlTemplateWOGroupBy = new StringTemplate(
			"SELECT <RESULT> FROM <<OP1>> AS <OP1>");

	// constructors
	public GenericAggregation(AbstractCompileOperator child) {
		super(child);

		this.groupExprs = new Vector<AbstractExpression>();
		this.aggExprs = new Vector<AbstractExpression>();
		this.aliases = new Vector<TokenIdentifier>();

		this.type = EnumOperator.GENERIC_AGGREGATION;
	}

	//copy-constructor
	public GenericAggregation(GenericAggregation toCopy) {
		super(toCopy);
		
		this.aggExprs = new Vector<AbstractExpression>();
		for (AbstractExpression ta : toCopy.aggExprs) {
			this.aggExprs.add(ta.clone());
		}
		
		this.groupExprs = new Vector<AbstractExpression>();
		for (AbstractExpression ae : toCopy.groupExprs) {
			this.groupExprs.add(ae.clone());
		}
		
		this.aliases = new Vector<TokenIdentifier>();
		for (TokenIdentifier ti : toCopy.aliases) {
			this.aliases.add(ti);
		}
		
		this.type = EnumOperator.GENERIC_AGGREGATION;
	}

	// getters and setters
	public void addAlias(TokenIdentifier alias) {
		aliases.add(alias);
	}

	public TokenIdentifier getAlias(int i) {
		return aliases.get(i);
	}

	public Vector<TokenIdentifier> getAliases() {
		return aliases;
	}

	public Vector<TokenIdentifier> getAggregationAliases() {
		Vector<TokenIdentifier> aggAliases = new Vector<TokenIdentifier>(this.aggExprs.size());
		for(int i=0; i<this.aggExprs.size();++i){
			aggAliases.add(this.aliases.get(i));
		}
		return aggAliases;
	}

	public Vector<TokenIdentifier> getGroupAliases() {
		Vector<TokenIdentifier> grpAliases = new Vector<TokenIdentifier>(this.groupExprs.size());
		for(int i=this.aggExprs.size(); i<this.aggExprs.size()+this.groupExprs.size();++i){
			grpAliases.add(this.aliases.get(i));
		}
		return grpAliases;
	}
	
	public void addGroupExpression(AbstractExpression expr) {
		this.groupExprs.add(expr);
	}

	public AbstractExpression getGroupExpression(int i) {
		return this.groupExprs.get(i);
	}

	public Collection<AbstractExpression> getGroupExpressions() {
		return groupExprs;
	}

	public void addAggregationExpression(AbstractExpression expr) {
		this.aggExprs.add(expr);
	}

	public AbstractExpression getAggregationExpression(int i) {
		return this.aggExprs.get(i);
	}

	public Collection<AbstractExpression> getAggregationExpressions() {
		return this.aggExprs;
	}

	//methods
	public void replaceExpression(Map<AbstractExpression, AbstractExpression> replaceExpr){
		Vector<AbstractExpression> newAggExprs = new Vector<AbstractExpression>(this.aggExprs.size());
		for(AbstractExpression aggExpr: this.aggExprs){
			newAggExprs.add(aggExpr.replaceExpressions(replaceExpr));
		}
		this.aggExprs = newAggExprs;
		
		Vector<AbstractExpression> newGroupExprs = new Vector<AbstractExpression>(this.groupExprs.size());
		for(AbstractExpression grpExpr: this.groupExprs){
			newGroupExprs.add(grpExpr.replaceExpressions(replaceExpr));
		}
		this.groupExprs = newGroupExprs;
	}
	
	@Override
	public String toSqlString() {
		final Map<String, String> vars = new HashMap<String, String>();
		vars.put("OP1", getChild().getOperatorId().toString());
		vars.put("AGG_ATTRS", SetUtils.stringifyExprVec(aggExprs));
		vars.put("GROUP_ATTRS", SetUtils.stringifyExprVec(groupExprs));

		final List<String> aliasVec = resultAttributesToSQL();
		final List<String> aggrAliases = aliasVec.subList(0, aggExprs.size());
		final List<String> grpAliases = aliasVec.subList(aggExprs.size(),
				aliasVec.size());

		final Vector<String> aggrExprVec = new Vector<String>(aggExprs.size());
		for (AbstractExpression exp : aggExprs) {
			aggrExprVec.add(exp.toSqlString());
		}
		final Vector<String> groupExprVec = new Vector<String>(
				groupExprs.size());
		for (AbstractExpression exp : groupExprs) {
			groupExprVec.add(exp.toSqlString());
		}

		StringBuilder resultString =  new StringBuilder(SetUtils.buildAliasString(aggrExprVec, aggrAliases));
		
		if(groupExprs.size()>0){
			resultString.append(AbstractToken.COMMA);
			resultString.append(AbstractToken.BLANK);
			resultString.append(SetUtils.buildAliasString(groupExprVec, grpAliases));
			vars.put("RESULT", resultString.toString());
			
			return this.sqlTemplate.toString(vars);
		}
		else{
			vars.put("RESULT", resultString.toString());
			return this.sqlTemplateWOGroupBy.toString(vars);
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
			footer.append("Aggregation: ");
			footer.append(this.aggExprs.toString());
			footer.append(AbstractToken.NEWLINE);
			footer.append("Grouping: ");
			footer.append(this.groupExprs.toString());
			footer.append(AbstractToken.NEWLINE);
			footer.append("Aliases: ");
			footer.append(this.aliases.toString());
			if (node.getInfo().getFooter() != null) {
				footer.append(AbstractToken.NEWLINE);
				footer.append(node.getInfo().getFooter());
			}
			node.getInfo().setFooter(footer.toString());
		}
		return err;
	}

	@Override
	public void renameTableOfAttributes(String oldId, String newId) {
		Vector<TokenAttribute> atts = new Vector<TokenAttribute>();
		for (AbstractExpression expr : this.aggExprs) {
			atts.addAll(expr.getAttributes());
		}
		for (AbstractExpression expr : this.groupExprs) {
			atts.addAll(expr.getAttributes());
		}
		TokenAttribute.renameTable(atts, oldId, newId);
	}

	@Override
	public void renameForPushDown(Collection<TokenAttribute> selAtts) {
		HashMap<TokenIdentifier, TokenIdentifier> renameMap = new HashMap<TokenIdentifier, TokenIdentifier>();
		for (int i = 0; i < this.groupExprs.size(); ++i) {
			AbstractExpression expr = this.groupExprs.get(i);
			if (expr.isAttribute()) {
				renameMap.put(expr.getAttribute().getName(),
						this.aliases.get(this.aggExprs.size() + i));
			}
		}
		TokenAttribute.rename(selAtts, this.getChild().getOperatorId()
				.toString(), renameMap);
	}

	@Override
	public GenericAggregation clone() throws CloneNotSupportedException {
		GenericAggregation ga = (GenericAggregation) super.clone();
		Vector<AbstractExpression> aev = new Vector<AbstractExpression>();
		for (AbstractExpression ta : this.aggExprs) {
			aev.add(ta.clone());
		}
		ga.aggExprs = aev;
		Vector<AbstractExpression> grouping = new Vector<AbstractExpression>();

		for (AbstractExpression ae : this.groupExprs) {
			grouping.add(ae.clone());
		}
		ga.groupExprs = grouping;
		Vector<TokenIdentifier> alias = new Vector<TokenIdentifier>();

		for (TokenIdentifier ti : this.aliases) {
			alias.add(ti);
		}

		ga.aliases = alias;

		return ga;
	}
}
