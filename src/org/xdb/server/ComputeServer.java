package org.xdb.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.logging.Level;

import org.xdb.Config;
import org.xdb.error.Error;
import org.xdb.execute.ComputeNode;
import org.xdb.execute.ComputeNodeDesc;
import org.xdb.execute.operators.AbstractExecuteOperator;
import org.xdb.execute.operators.EnumOperatorStatus;
import org.xdb.execute.signals.CloseSignal;
import org.xdb.execute.signals.KillSignal;
import org.xdb.execute.signals.ReadySignal;
import org.xdb.execute.signals.RestartSignal;
import org.xdb.logging.EnumXDBComponents;
import org.xdb.utils.Identifier;

/**
 * Server which accepts compute commands and calls handler (separate thread)
 * 
 * @author cbinnig
 * 
 */
public class ComputeServer extends AbstractServer {

	/**
	 * Handle for compute commands
	 * 
	 * @author cbinnig
	 * 
	 */
	private class Handler extends AbstractHandler {
		// constructor
		public Handler(final Socket client) {
			super(client);
			logger = ComputeServer.this.logger;
		}

		/**
		 * Handle incoming cmd
		 * 
		 * @return
		 * @throws IOException
		 */
		@Override
		protected Error handle(final ObjectOutputStream out, final ObjectInputStream in) throws IOException {
			Error err = new Error();


			final int cmd = in.readInt();
			
			try {

				switch (cmd) {
				case CMD_STOP_SERVER:
					ComputeServer.this.stopServer();
					break;
				case CMD_PING_SERVER:
					break;
				case CMD_PING_OPERATOR:
					final Identifier opID = (Identifier) in.readObject();
					EnumOperatorStatus opStatus = compute.pingOperator(opID);
					out.writeObject(opStatus);
					break;
				case CMD_OPEN_OP:
					final AbstractExecuteOperator op = (AbstractExecuteOperator) in.readObject();
					logger.log(Level.INFO, "Received operator:" + op.getOperatorId());
					err = compute.openOperator(op);
					out.writeObject(op.getStatus());
					break;
				case CMD_READY_SIGNAL:
					final ReadySignal readSignal = (ReadySignal) in.readObject();
					logger.log(Level.INFO, "Received ready signal for operator:" + readSignal.getConsumer());
					err = compute.signalOperator(readSignal);
					break;
				case CMD_CLOSE_SIGNAL:
					final CloseSignal closeSignal = (CloseSignal) in.readObject();
					logger.log(Level.INFO, "Received close signal for operator:" + closeSignal.getExecuteOperator().getOperatorId());
					err = compute.closeOperator(closeSignal);
					break; 
				case CMD_KILL_SIGNAL: 
					final KillSignal killSignal = (KillSignal) in.readObject(); 
					logger.log(Level.INFO, "Received kill signal for operator:" + killSignal.getFailedExecOpId());
                    err = compute.killOperator(killSignal); 
                    break; 
				case CMD_RESTART_SERVER: 
					final RestartSignal restartSignal = (RestartSignal) in.readObject();  
					logger.log(Level.INFO, "Received restart server signal");
                    err = ComputeServer.this.killServer(restartSignal); 
                    break;
				default:
					err = createCmdError(cmd);
					break;
				}
			} catch (final Exception e) {
				err = createServerError(e);
			}

			return err;
		}
	}

	// constants for commands
	public static final int CMD_OPEN_OP = 1;
	public static final int CMD_READY_SIGNAL = 2;
	public static final int CMD_CLOSE_SIGNAL = 3;
	public static final int CMD_KILL_SIGNAL = 4;

	// Compute node which executes commands
	private final ComputeNode compute;

	// constructors
	public ComputeServer(final int port) throws Exception {
		super(EnumXDBComponents.COMPUTE_SERVER);

		this.port = port;
		this.compute = new ComputeNode(port);
	}
    
	// restart the server for DoomDB/Fault Tolerance 
	public Error killServer(RestartSignal restartSignal) {
		long startTime = System.currentTimeMillis();
		
		// stop compute server  
		this.stopServer();
		
		// kill all running queries
		MysqlRunManager sqlManager = new MysqlRunManager();
		this.err = sqlManager.killAllQueries();
		if(this.err.isError())  { 
			System.out.println("Error in killing the query!");
			return this.err; 
		}
		System.out.println("killing all queries passed");
		
		long endTime = System.currentTimeMillis();
		long waitTime = endTime -startTime;
		
		//wait MTTR	- waitTime
		try {
			long mttr = restartSignal.getTimeToRepair();
			if(waitTime<mttr){
				mttr = mttr - waitTime;
				Thread.sleep(mttr);
			}
			
		} catch (InterruptedException e) {
			e.printStackTrace();
		}  
		System.out.println("Ready to start the server");
		//restart compute server
		this.restartServer(); 
		System.out.println("server is restardted "+this.err);
		
		return this.err;
	}

	// methods
	/**
	 * Handle incoming client requests
	 */
	@Override
	protected void handle(final Socket client) {
		final Handler handler = new Handler(client);
		handler.start();
	}
	
	public ComputeNodeDesc getComputeNode(){
		return this.compute.getComputeNode();
	}

	public synchronized void restartServer(){
		super.startServer();
		
		this.err = this.compute.startup(true);
	}
	
	@Override
	public synchronized Error  startServer(){
		this.err = super.startServer();
		if(this.err.isError())
			return err;
		
		this.err = this.compute.startup(false);
		return this.err;
	}
	
	@Override
	public synchronized void stopServer(){
		super.stopServer();
		
		this.compute.shutdown();
	}
	
	/**
	 * Start server from command line
	 * 
	 * @param args
	 * @throws UnknownHostException 
	 */
	public static void main(final String[] args) throws Exception {
		int port = Config.COMPUTE_PORT;
		if(args.length>=1){
			port = Integer.parseInt(args[0]);
		}
		final ComputeServer server = new ComputeServer( port );
		server.startServer();
		
		if(server.getError().isError()){
			server.stopServer();
			System.out.println("Compute server error ("+server.getError()+")");
		}
	}
}
