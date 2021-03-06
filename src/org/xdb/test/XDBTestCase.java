package org.xdb.test;

import org.xdb.Config;
import org.xdb.server.CompileServer;
import org.xdb.server.ComputeServer;
import org.xdb.server.MasterTrackerServer;
import org.xdb.server.QueryTrackerServer;

public class XDBTestCase extends TestCase {
	protected MasterTrackerServer mTrackerServer;
	protected QueryTrackerServer qTrackerServer;
	protected ComputeServer computeServer;
	
	public XDBTestCase() {
		super();
	}
	
	@Override
	public void setUp(){
		assertNoError(CompileServer.deleteCatalog());

		try {
			mTrackerServer = new MasterTrackerServer();
			mTrackerServer.startServer();
			assertNoError(mTrackerServer.getError());
			
			qTrackerServer = new QueryTrackerServer();
			qTrackerServer.startServer();
			assertNoError(qTrackerServer.getError());
		
			computeServer = new ComputeServer(Config.COMPUTE_PORT);
			computeServer.startServer();
			assertNoError(computeServer.getError());
			
		} catch (Exception e) {
			TestCase.assertTrue(e.toString(), false);
		}
	}
	
	@Override
	public void tearDown(){
		mTrackerServer.stopServer();
	}
}