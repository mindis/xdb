package org.xdb.test.funsql.compile;

import org.junit.Test;
import org.xdb.funsql.compile.FunSQLCompiler;
import org.xdb.funsql.statement.AbstractServerStmt;
import org.xdb.test.TestCase;
import org.xdb.test.XDBTestCase;

public class TestCreateConnectionSQL extends XDBTestCase {
	@Test
	public void testSimpleCreate() {
		FunSQLCompiler compiler = new FunSQLCompiler();
		
		//create connection -> no error
		String createConnSql = 
				"CREATE CONNECTION \"testConnection\" " +
				"URL 'jdbc:mysql://127.0.0.1/xdb' " +
				"USER 'xroot' " +
				"PASSWORD 'xroot' " +
				"STORE 'XDB' ";
		
		AbstractServerStmt stmt = compiler.compile(createConnSql);
		this.assertNoError(compiler.getLastError());
		TestCase.assertNotNull(stmt);
		this.execute(stmt);
		
		String dropConnSql = "DROP CONNECTION testConnection";
		stmt = compiler.compile(dropConnSql);
		this.assertError(compiler.getLastError());
		
		dropConnSql = "DROP CONNECTION \"testConnection\"";
		stmt = compiler.compile(dropConnSql);
		this.assertNoError(compiler.getLastError());
		TestCase.assertNotNull(stmt);
		this.execute(stmt);

	}
}
