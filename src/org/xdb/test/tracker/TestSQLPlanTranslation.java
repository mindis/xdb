package org.xdb.test.tracker;

import org.junit.Test;
import org.xdb.error.Error;
import org.xdb.funsql.compile.CompilePlan;
import org.xdb.funsql.compile.FunSQLCompiler;
import org.xdb.funsql.compile.analyze.operator.MaterializationAnnotationVisitor;
import org.xdb.funsql.statement.AbstractServerStmt;
import org.xdb.funsql.statement.CreateFunctionStmt;
import org.xdb.test.TestCase;
import org.xdb.test.XDBTestCase;
import org.xdb.tracker.QueryTrackerPlan;
import org.xdb.utils.Tuple;

public class TestSQLPlanTranslation extends XDBTestCase {

	
	@Test
	public void testSimpleCreate() {
		FunSQLCompiler compiler = new FunSQLCompiler();
		compiler.doOptimize(true);
		
		// create connection -> no error
		String dropConnSql = "DROP CONNECTION \"testConnection\"";
		AbstractServerStmt stmt = compiler.compile(dropConnSql);
		if (stmt != null)
			this.execute(stmt);

		String createConnSql = "CREATE CONNECTION \"testConnection\" "
				+ "URL 'jdbc:mysql://127.0.0.1/xdb_tmp' " + "USER 'xroot' "
				+ "PASSWORD 'xroot' " + "STORE 'XDB' ";

		stmt = compiler.compile(createConnSql);
		this.assertNoError(compiler.getLastError());
		TestCase.assertNotNull(stmt);
		this.execute(stmt);

		// create table
		String dropTableSql = "DROP TABLE \"R\"";
		stmt = compiler.compile(dropTableSql);
		if (stmt != null)
			this.execute(stmt);

		String createTableStmt = "CREATE TABLE \"R\"( " + "  A INT,"
				+ "  B VARCHAR," + "  C INT"
				+ ") IN CONNECTION \"testConnection\"";

		stmt = compiler.compile(createTableStmt);
		this.assertNoError(compiler.getLastError());
		TestCase.assertNotNull(stmt);
		this.execute(stmt);

		dropTableSql = "DROP TABLE \"S\"";
		stmt = compiler.compile(dropTableSql);
		if (stmt != null)
			this.execute(stmt);

		createTableStmt = "CREATE TABLE \"S\"( " + "  D INT," + "  E VARCHAR,"
				+ "  F INT" + ") IN CONNECTION \"testConnection\"";

		stmt = compiler.compile(createTableStmt);
		this.assertNoError(compiler.getLastError());
		TestCase.assertNotNull(stmt);
		this.execute(stmt);

		// execute CreateFunction
		CreateFunctionStmt fStmt = (CreateFunctionStmt) compiler.compile(""
				+ "CREATE FUNCTION f1( OUT o1 TABLE, OUT o2 TABLE) \n" 
				+ "BEGIN \n"
				+ "VAR v1 = SELECT R1.A AS A1, R2.D AS A2 "
					+ "FROM R AS R1, S AS R2 " 
					+ "WHERE R1.B=R2.E AND R1.C=1; \n"
				+ "VAR v2 = SELECT V1.A1 AS A, V2.F AS B "
					+ "FROM :v1 AS V1, S AS V2 " 
					+ "WHERE V1.A1=3 AND V2.F=V1.A2; \n"
				+ ":o1 = SELECT R1.A FROM :v2 as R1; \n"
				+ ":o2 = SELECT R1.A2 FROM :v1 as R1 "
					+ "WHERE R1.A1=1; \n"
				+ "END; ");
		this.assertNoError(compiler.getLastError());
		fStmt.getPlan().tracePlan(this.getClass().getName()+"_Compiler");
		this.assertNoError(fStmt.execute());
		
		final CompilePlan plan = fStmt.getPlan();
		
		Error annotation = this.annotateCompilePlan(plan);
		assertNoError(annotation);
		
		Tuple<QueryTrackerPlan, Error> qPlan = qTrackerServer.getNode().generateQueryTrackerPlan(plan);
		assertNoError(qPlan.getObject2());
		TestCase.assertNotNull(qPlan.getObject1());
		qPlan.getObject1().tracePlan(this.getClass().getName()+"_Tracker");
		
		assertEquals(3, qPlan.getObject1().getTrackerOperators().size());
	}
	
	private Error annotateCompilePlan(CompilePlan cplan) {
		Error err = new Error();
		MaterializationAnnotationVisitor mvisitor = new MaterializationAnnotationVisitor();
		cplan.applyVisitor(mvisitor);
		
		return err;
	}
}