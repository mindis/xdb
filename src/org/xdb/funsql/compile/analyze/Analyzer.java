package org.xdb.funsql.compile.analyze;

import java.util.HashMap;
import java.util.Map;

import org.xdb.funsql.compile.CompilePlan;
import org.xdb.funsql.compile.analyze.operator.CheckOperatorVisitor;
import org.xdb.funsql.compile.analyze.operator.CreateResultVisitor;
import org.xdb.funsql.compile.analyze.operator.RenameOperatorVisitor;
import org.xdb.funsql.compile.operator.AbstractCompileOperator;
import org.xdb.funsql.compile.tokens.AbstractToken;
import org.xdb.funsql.compile.tokens.TokenAttribute;
import org.xdb.funsql.types.EnumSimpleType;
import org.xdb.utils.Identifier;

public class Analyzer {
	private CompilePlan compilePlan;
	private Map<AbstractToken, EnumSimpleType> expTypes = new HashMap<AbstractToken, EnumSimpleType>();

	public Analyzer(CompilePlan compilePlan,
			Map<TokenAttribute, EnumSimpleType> attTypes) {
		super();
		this.compilePlan = compilePlan;
		this.expTypes.putAll(attTypes);
	}

	public void analyze() {
		// Check and derive types for each operator result
		for (Identifier rootId : compilePlan.getRootIds()) {
			AbstractCompileOperator root = this.compilePlan.getOperator(rootId);

			CheckOperatorVisitor checkOpVisitor = new CheckOperatorVisitor(
					root, this.expTypes);
			checkOpVisitor.visit();
		}

		// Create result descriptions for each operator result
		for (Identifier rootId : compilePlan.getRootIds()) {
			AbstractCompileOperator root = this.compilePlan.getOperator(rootId);

			CreateResultVisitor resultDescVisitor = new CreateResultVisitor(
					root, expTypes);
			resultDescVisitor.visit();
		}

		// Rename attributes for optimization (e.g., from R1.A to 1_1.R1_A)
		for (Identifier rootId : compilePlan.getRootIds()) {
			AbstractCompileOperator root = this.compilePlan.getOperator(rootId);

			RenameOperatorVisitor renameVisitor = new RenameOperatorVisitor(
					root);
			renameVisitor.visit();
		}
	}
}
