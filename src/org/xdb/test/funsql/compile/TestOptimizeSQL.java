package org.xdb.test.funsql.compile;

import junit.framework.Assert;

import org.junit.Test;
import org.xdb.funsql.compile.CompilePlan;
import org.xdb.funsql.compile.FunSQLCompiler;
import org.xdb.funsql.optimize.Optimizer;
import org.xdb.funsql.statement.AbstractServerStmt;
import org.xdb.funsql.statement.SelectStmt;
import org.xdb.test.CompileServerTestCase;

public class TestOptimizeSQL extends CompileServerTestCase {

		@Test
		public void testSimpleOptimizer() {
			FunSQLCompiler compiler = new FunSQLCompiler();

			String dropConnSql = "DROP CONNECTION \"testConnection\"";
			AbstractServerStmt stmt = compiler.compile(dropConnSql);
			if (stmt != null)
				this.execute(stmt);

			// create connection -> no error
			String createConnSql = "CREATE CONNECTION \"testConnection\" "
					+ "URL 'jdbc:mysql://127.0.0.1/xdb_tmp' " + "USER 'xroot' "
					+ "PASSWORD 'xroot' " + "STORE 'XDB' ";

			stmt = compiler.compile(createConnSql);
			this.assertNoError(compiler.getLastError());
			Assert.assertNotNull(stmt);
			this.execute(stmt);

			SelectStmt selectStmt = (SelectStmt) compiler
					.compile("SELECT R1.A AS A1, R2.A AS A2 "
							+ "FROM R AS R1, R AS R2, R AS R3 "
							+ "WHERE R1.B=R2.B AND R2.B=R3.B AND R1.C=1");
			this.assertNoError(compiler.getLastError());

			selectStmt.getPlan().findPartenRelations();
			Optimizer opti = new Optimizer(selectStmt.getPlan());
			opti.optimize();
			selectStmt.getPlan().traceGraph(this.getClass().getName());
			
		}

	
	
}
