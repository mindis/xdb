package org.xdb.funsql.codegen;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.xdb.Config;
import org.xdb.error.EnumError;
import org.xdb.error.Error;
import org.xdb.funsql.compile.CompilePlan;
import org.xdb.funsql.compile.analyze.operator.ConnectionAnnotationVisitor;
import org.xdb.funsql.compile.operator.AbstractCompileOperator;
import org.xdb.funsql.compile.operator.ResultDesc;
import org.xdb.funsql.compile.operator.TableOperator;
import org.xdb.funsql.compile.tokens.AbstractToken;
import org.xdb.metadata.Connection;
import org.xdb.tracker.QueryTrackerPlan;
import org.xdb.tracker.operator.AbstractTrackerOperator;
import org.xdb.tracker.operator.MySQLTrackerOperator;
import org.xdb.tracker.operator.TableDesc;
import org.xdb.utils.Identifier;
import org.xdb.utils.StringTemplate;

/**
 * Generates a query tracker plan form a given compile plan
 * 
 * @author cbinnig
 * 
 */
public class CodeGenerator {
	// constants
	private static final String VIEW1 = "VIEW1";
	private static final String TAB1 = "TAB1";
	private static final String SQL1 = "SQL1";
	private static final String PART1 = "PART1";
	public static final String OUT_PREFIX = "OUT";
	public static final String PART_PREFIX = "P";
	//public static final String TABLE_PREFIX = "_";

	// compile plan
	private CompilePlan compilePlan;

	// generated query tracker plan
	private QueryTrackerPlan qtPlan = new QueryTrackerPlan();

	// sources / consumers in query tracker plan: operator Id -> operator IDs
	private Map<Identifier, Set<Identifier>> sources = new HashMap<Identifier, Set<Identifier>>();
	private Map<Identifier, Set<Identifier>> consumers = new HashMap<Identifier, Set<Identifier>>();

	// mapping: compile operator ID -> tracker operator ID
	private Map<Identifier, List<Identifier>> compileOp2trackerOp = new HashMap<Identifier, List<Identifier>>();

	// roots of sub-plans: each sub-plan results in one tracker operator
	private List<Identifier> splitOpIds;

	// templates for SQL code generation
	private final StringTemplate sqlInsertSelectTemplate = new StringTemplate(
			"INSERT INTO <<" + TAB1 + ">> (<" + SQL1 + ">)");

	private final StringTemplate sqlInOutDDLTemplate = new StringTemplate("<<"
			+ TAB1 + ">> <" + SQL1 + ">");

	private final StringTemplate sqlViewSelectPartTemplate = new StringTemplate(
			"<<" + VIEW1 + ">> AS SELECT * FROM <<" + TAB1 + ">>  PARTITION(P<"
					+ PART1 + ">)");

	private final StringTemplate sqlSelectAllTemplate = new StringTemplate("<<"
			+ TAB1 + ">>");

	private final StringTemplate sqlViewTemplate = new StringTemplate("<<"
			+ VIEW1 + ">> AS <" + SQL1 + ">");
   
	private double trackerOpRuntime;
	// constructor
	public CodeGenerator(CompilePlan compilePlan) {
		super();
		this.compilePlan = compilePlan;
	}

	// getter and setter
	public QueryTrackerPlan getQueryTrackerPlan() {
		return this.qtPlan;
	}

	private void addCompileOp2TrackerOp(Identifier compileId,
			Identifier trackerId) {
		List<Identifier> trackerIds;

		if (!this.compileOp2trackerOp.containsKey(compileId)) {
			trackerIds = new ArrayList<Identifier>();
			this.compileOp2trackerOp.put(compileId, trackerIds);
		} else {
			trackerIds = this.compileOp2trackerOp.get(compileId);
		}
		trackerIds.add(trackerId);
	}

	// methods
	/**
	 * Generate QueryTrackerPlan from CompilePlan
	 * 
	 * @return
	 */
	public Error generate() {
		Error err = new Error();
	
		// split compile plan into sub-plans
		this.splitOpIds = extractSplitOps();
		
		if(!Config.SIMULATION_MODE){
			// optimize plan for code generation
			err = this.optimize();
			if (err.isError())
				return err; 
		}
		
		
		// annotate compile plan with connections
		err = this.annotateConnections();
		if (err.isError())
			return err;

		// rename attributes to original names
		err = this.rename();
		if (err.isError())
			return err;
				
		// update Compile Ops runtim 
		if(Config.SIMULATION_MODE)
		    err = this.updateCompileOpsRuntime();
		
		// trace compile plan before generating a tracker plan
		if (Config.TRACE_CODEGEN_PLAN) {
			this.compilePlan.tracePlan(compilePlan.getClass()
					.getCanonicalName() + "_CODEGEN");
		} 

		// generate a tracker operators
		err = this.genTrackerPlan();
		if (err.isError())
			return err;

		return err;
	}

	private Error updateCompileOpsRuntime() {
		Error err = new Error();
		Collection<Identifier> roots = this.compilePlan.getRootIds(); 
		for (Identifier root : roots) {
			AbstractCompileOperator rootOp = this.compilePlan.getOperator(root);
			updateRuntime(rootOp);
		}
		return err;
	}

	private void updateRuntime(AbstractCompileOperator rootOp) {
		// TODO Auto-generated method stub 
		Vector<AbstractCompileOperator> childrenOps = rootOp.getChildren(); 
		for (AbstractCompileOperator child : childrenOps) {
			updateRuntime(child);
		} 
		
		if(this.splitOpIds.contains(rootOp.getOperatorId())){
			
			rootOp.setRuntime((trackerOpRuntime + 
					this.compilePlan.getQueryStats().getQueryRuntimesStat().get(rootOp.getOperatorId().getChildId())*Config.COMPILE_FT_PIPELINE_CNST)); 
			rootOp.setMattime(this.compilePlan.getQueryStats().getQueryMattimesStat().get(rootOp.getOperatorId().getChildId())); 
			trackerOpRuntime = 0;
		} else { 
			if(this.compilePlan.getQueryStats().getQueryRuntimesStat().containsKey(rootOp.getOperatorId().getChildId()))
			     trackerOpRuntime += this.compilePlan.getQueryStats().getQueryRuntimesStat().get(rootOp.getOperatorId().getChildId()); 
		}
	}

	/**
	 * Optimize compile plan for MySQL code generation
	 */
	private Error optimize() {
		
		Error err = new Error();
		if (!Config.CODEGEN_OPTIMIZE)
			return err;

		JoinCombineVisitor joinCombineVisitor = new JoinCombineVisitor(
				this.compilePlan);
		SQLUnaryCombineVisitor unaryCombineVisitor = new SQLUnaryCombineVisitor(
				this.compilePlan);
		SQLCombineVisitor sqlCombineVisitor = new SQLCombineVisitor(
				this.compilePlan);
		

		int i = 1;
		for (Identifier splitOpId : this.splitOpIds) {
			// get splitOp from compile plan as root for optimization
			AbstractCompileOperator splitOp = this.compilePlan
					.getOperator(splitOpId);
			joinCombineVisitor.reset(splitOp);
			err = joinCombineVisitor.visit();
			if (err.isError())
				return err;
	       
			if (Config.TRACE_CODEGEN_PLAN)
				this.compilePlan.tracePlan(compilePlan.getClass()
						.getCanonicalName()
						+ "_CODEGEN_PHASE"
						+ i
						+ "_1CombinedJoins");

			// get splitOp again since it might have be replaced
			splitOp = this.compilePlan.getOperator(splitOpId);
			unaryCombineVisitor.reset(splitOp);
			err = unaryCombineVisitor.visit();
			if (err.isError())
				return err;

			if (Config.TRACE_CODEGEN_PLAN)
				this.compilePlan.tracePlan(compilePlan.getClass()
						.getCanonicalName()
						+ "_CODEGEN_PHASE"
						+ i
						+ "_2CombinedUnaries");

			// get splitOp again since it might have be replaced
			splitOp = this.compilePlan.getOperator(splitOpId);
			sqlCombineVisitor.reset(splitOp);
			err = sqlCombineVisitor.visit();
			if (err.isError())
				return err;

			if (Config.TRACE_CODEGEN_PLAN)
				this.compilePlan.tracePlan(compilePlan.getClass()
						.getCanonicalName()
						+ "_CODEGEN_PHASE"
						+ i
						+ "_3Combined");
			++i;
		}
		return err;
	}

	private Error annotateConnections() {
		Error err = new Error();
		ConnectionAnnotationVisitor visitor = new ConnectionAnnotationVisitor();
		this.compilePlan.applyVisitor(visitor);
		return err;
	}

	/**
	 * Renames attributes to original names in tables e.g., from
	 * LINEITEM_L_ORDERKEY to L_ORDERKEY
	 */
	private Error rename() {
		Error err = new Error();
		ReRenameAttributesVisitor renameVisitor;
		for (AbstractCompileOperator root : this.compilePlan
				.getRootOps()) {
			renameVisitor = new ReRenameAttributesVisitor(root);
			err = renameVisitor.visit();

			if (err.isError())
				return err;
		}
		return err;
	}

	/**
	 * Generates (parallelized) QueryTrackerPlan (which includes repartitioning)
	 */
	private Error genTrackerPlan() {
		Error err = new Error();
       
		// for each sub-plan generate a tracker operator
		for (Identifier splitOpId : this.splitOpIds) {
			AbstractCompileOperator splitCompileOp = this.compilePlan
					.getOperator(splitOpId);  
			AbstractTrackerOperator trackerOp = null;
			try {
				ResultDesc splitResult = splitCompileOp.getResult();

				// generate one tracker operator for each partition
				for (int i = 0; i < splitResult.getPartitionCount(); ++i) {
					trackerOp = this.genTrackerOp(splitCompileOp, i);
					// add mapping: compile operator -> tracker operator
					this.addCompileOp2TrackerOp(splitCompileOp.getOperatorId(),
							trackerOp.getOperatorId());
				}

			} catch (URISyntaxException e) {
				String[] args = { e.toString() };
				err = new Error(EnumError.COMPILER_GENERIC, args);
				return err;
			}
		}

		// connect tracker operator in QueryTrackerPlan
		for (Map.Entry<Identifier, Set<Identifier>> entry : this.sources
				.entrySet()) {
			this.qtPlan.setSources(entry.getKey(), entry.getValue());
		}

		for (Map.Entry<Identifier, Set<Identifier>> entry : this.consumers
				.entrySet()) {
			this.qtPlan.setConsumers(entry.getKey(), entry.getValue());
		}

		return err;
	}

	/**
	 * Adds execute statements to new tracker operator
	 * 
	 * @param trackerOp
	 * @param compileOp
	 */
	private void addTrackerExecuteDML(MySQLTrackerOperator trackerOp,
			AbstractCompileOperator compileOp) {
		// add DML statement for execution
		Identifier outTableId = this.genOutputTableName(compileOp);
		String outTableName = outTableId.toString();
		String executeDML = genExecuteDML(compileOp);
		Map<String, String> args = new HashMap<String, String>();
		args.put(SQL1, executeDML);
		args.put(TAB1, outTableName);
		executeDML = this.sqlInsertSelectTemplate.toString(args);
		trackerOp.addExecuteSQL(new StringTemplate(executeDML));
	}

	/**
	 * Adds DDL statements for output tables/views to new tracker operator
	 * 
	 * @param trackerOp
	 * @param compileOp
	 */
	private void addTrackerOutputDDL(MySQLTrackerOperator trackerOp,
			AbstractCompileOperator compileOp, int partNum) {
		
		Identifier outTableId = this.genOutputTableName(compileOp);
		String outTableName = outTableId.toString();

		Map<String, String> args = new HashMap<String, String>();
		ResultDesc outputResult = compileOp.getResult();
		String outAttsDDL = outputResult.getAttsDDL(Config.COMPUTE_INTERMEDIATE_KEYS);
		args.put(SQL1, outAttsDDL);
		args.put(TAB1, outTableName);
		outAttsDDL = this.sqlInOutDDLTemplate.toString(args);

		// add output table w repartition specification
		if (outputResult.repartition()
				&& outputResult.getRePartitionCount() > 1) {
			String repartitionDDL = outputResult.getRepartDDL();
			trackerOp.addOutTable(outTableName, outAttsDDL, repartitionDDL);

			// add one output view for each partition
			for (Integer i = 0; i < outputResult.getPartitionCount(); ++i) {
				Identifier outViewId = this.genOutputTableName(compileOp,
						partNum);
				outViewId.append(i);
				String outputViewName = outViewId.toString();

				args.put(TAB1, outTableName);
				args.put(PART1, i.toString());
				args.put(VIEW1, outputViewName);

				String selectPartDML = sqlViewSelectPartTemplate.toString(args);

				trackerOp.addOutView(outputViewName, selectPartDML);
			}
		}
		// add output table w/o repartition specification
		else {
			trackerOp.addOutTable(outTableName, outAttsDDL);
		}
	}

	/**
	 * Adds DDL statements for input tables/views to new tracker operator
	 * 
	 * @param trackerOp
	 * @param compileOp
	 * @param partNum
	 */
	private void addTrackerInputDDL(MySQLTrackerOperator trackerOp,
			AbstractCompileOperator compileOp, int partNum) {
		Map<String, String> args = new HashMap<String, String>();
		Set<AbstractCompileOperator> inputCompileOps = this
				.getInputOps(compileOp); 
	     
		trackerOp.setRunime(compileOp.getRuntime()); 
		trackerOp.setMattime(compileOp.getMattime());
		// for each input operator create input DDL
		for (AbstractCompileOperator inputCompileOp : inputCompileOps) { 
			// generate input DDL
			ResultDesc inputResult = inputCompileOp.getResult();
			/** Create input table DDL **/
			Identifier inTableId = this.genInputTableName(inputCompileOp);
			String inTableName = inTableId.toString();
			String inAttsDDL = inputResult.getAttsDDL(false);
			args.put(SQL1, inAttsDDL);

			// if input is a table -> use a different name
			//if (inputCompileOp.isTable()) {
			//	inTableName = TABLE_PREFIX + inTableName;
			//}

			// if input is has more than one partition
			if (inputResult.repartition()) {
				StringBuffer sqlUnionDML = new StringBuffer();
				for (int i = 0; i < inputResult.getPartitionCount(); ++i) {
					// input table name
					Identifier inPartId = this
							.genInputTableName(inputCompileOp);
					inPartId.append(i);
					String inPartName = inPartId.toString();
					args.put(TAB1, inPartName);

					// input table DDL
					inAttsDDL = this.sqlInOutDDLTemplate.toString(args);
					trackerOp.addInTable(inPartName, new StringTemplate(
							inAttsDDL));

					// input view DDL
					if (i > 0) {
						sqlUnionDML.append(AbstractToken.BLANK);
						sqlUnionDML.append(AbstractToken.UNION);
						sqlUnionDML.append(AbstractToken.BLANK);
					}
					sqlUnionDML.append(AbstractToken.LBRACE);
					sqlUnionDML
							.append(this.sqlSelectAllTemplate.toString(args));
					sqlUnionDML.append(AbstractToken.RBRACE);
				}

				args.put(VIEW1, inTableName);
				args.put(SQL1, sqlUnionDML.toString());
				trackerOp.addInView(inTableName,
						this.sqlViewTemplate.toString(args));

			} else {

				args.put(TAB1, inTableName);
				inAttsDDL = this.sqlInOutDDLTemplate.toString(args);
				trackerOp
						.addInTable(inTableName, new StringTemplate(inAttsDDL));
			}

			/** Create input table description and dependencies **/
			// if input is a table: add catalog info to tracker operator
			if (inputCompileOp.isTable()) { // table
				TableOperator inputTableOp = (TableOperator) inputCompileOp;

				if (inputTableOp.isPartitioned()) {
					TableDesc tableDesc = new TableDesc(
							inputTableOp.getTableName(partNum),
							inputTableOp.getURIs(partNum));
					trackerOp.addInTableFederated(inTableName, tableDesc);
				} else {
					TableDesc tableDesc = new TableDesc(
							inputTableOp.getTableName(), inputTableOp.getURIs(partNum));
					trackerOp.addInTableFederated(inTableName, tableDesc);
				}
			}
			// else: use intermediate result as table (with _OUT suffix)
			else {
				// if input is has more than one partition
				if (inputResult.repartition()) {
					// create one input per partition
					int remotePartNum = 0;
					for (Identifier inTrackerOpId : this.compileOp2trackerOp
							.get(inputCompileOp.getOperatorId())) {

						// remote table name
						Identifier inPartRemoteId = null;
						if (inputCompileOp.getResult().getRePartitionCount() > 1) {
							inPartRemoteId = this.genOutputTableName(
									inputCompileOp, remotePartNum);
							inPartRemoteId.append(partNum);
						} else {
							inPartRemoteId = this
									.genOutputTableName(inputCompileOp);
						}

						// local table name
						Identifier inPartId = this
								.genInputTableName(inputCompileOp);
						inPartId.append(remotePartNum);
						String inPartName = inPartId.toString();

						// table description
						TableDesc tableDesc = new TableDesc(
								inPartRemoteId.toString(), inTrackerOpId);
						trackerOp.addInTableFederated(inPartName, tableDesc);
						this.addTrackerDependency(inTrackerOpId,
								trackerOp.getOperatorId());
						remotePartNum++;
					}
				} else {
					// create one input table
					Identifier inTableRemoteId = this
							.genOutputTableName(inputCompileOp);
					
					Identifier inTrackerOpId = null;
					if (inputCompileOp.getResult().getPartitionCount() > 1) {
						inTrackerOpId = this.compileOp2trackerOp.get(
							inputCompileOp.getOperatorId()).get(partNum);
					}
					else{
						inTrackerOpId = this.compileOp2trackerOp.get(
								inputCompileOp.getOperatorId()).get(0);
					}
					
					TableDesc tableDesc = new TableDesc(
							inTableRemoteId.toString(), inTrackerOpId);
					trackerOp.addInTableFederated(inTableName, tableDesc);
					this.addTrackerDependency(inTrackerOpId,
							trackerOp.getOperatorId());
				}
			}
		} 

	}

	/**
	 * Generates a tracker operator for each sub-plan (given as root of sub-plan
	 * in compile plan)
	 * 
	 * @param compileOp
	 * @return
	 * @throws URISyntaxException
	 */
	private AbstractTrackerOperator genTrackerOp(
			AbstractCompileOperator compileOp, int partNum)
			throws URISyntaxException {
	    
		// generate a new MySQL operator
		MySQLTrackerOperator trackerOp = new MySQLTrackerOperator();
		this.qtPlan.addOperator(trackerOp);

		// add DML statement for execution
		this.addTrackerExecuteDML(trackerOp, compileOp);

		// add DDL statements for output tables
		this.addTrackerOutputDDL(trackerOp, compileOp, partNum);

		// add DDL statements for input tables
		this.addTrackerInputDDL(trackerOp, compileOp, partNum); 
		
		// add connections to tracker operator
		List<Connection> trackerOpConnections = compileOp
				.getWishedConnections(partNum);
		trackerOp.setTrackerOpConnections(trackerOpConnections);

		return trackerOp;
	}

	/**
	 * Register dependency
	 * 
	 * @param fromOp
	 * @param toOp
	 */
	private void addTrackerDependency(Identifier fromOp, Identifier toOp) {
		addTrackerDependency(this.sources, fromOp, toOp);
		addTrackerDependency(this.consumers, toOp, fromOp);
	}

	/**
	 * Register dependency
	 * 
	 * @param dependencies
	 * @param fromOp
	 * @param toOp
	 */
	private void addTrackerDependency(
			Map<Identifier, Set<Identifier>> dependencies, Identifier fromOp,
			Identifier toOp) {
		Set<Identifier> opIds;
		if (!dependencies.containsKey(toOp)) {
			opIds = new HashSet<Identifier>();
			dependencies.put(toOp, opIds);
		} else {
			opIds = dependencies.get(toOp);
		}
		opIds.add(fromOp);
	}

	/**
	 * Get input operators for given split Operator (i.e., root of sub-plan)
	 * 
	 * @param compileOp
	 * @return
	 */
	private Set<AbstractCompileOperator> getInputOps(
			AbstractCompileOperator compileOp) {

		Set<AbstractCompileOperator> inputOps = new HashSet<AbstractCompileOperator>();
		for (AbstractCompileOperator childOp : compileOp.getChildren()) {
			getInputOps(inputOps, childOp);
		}
		return inputOps;
	}

	/**
	 * Get input operators for given compile operator (recursively called)
	 * 
	 * @param inputOps
	 * @param compileOp
	 */
	private void getInputOps(Set<AbstractCompileOperator> inputOps,
			AbstractCompileOperator compileOp) {

		if (this.splitOpIds.contains(compileOp.getOperatorId())
				|| compileOp.isLeaf()) {
			inputOps.add(compileOp);
			return;
		}

		for (AbstractCompileOperator childOp : compileOp.getChildren()) {
			getInputOps(inputOps, childOp);
		}
	}

	/**
	 * Generate DML statement for execution
	 * 
	 * @param compileOp
	 * @return
	 */
	private String genExecuteDML(AbstractCompileOperator compileOp) {
		Map<String, String> args = new HashMap<String, String>();
		String executeDML = compileOp.toSqlString();

		StringTemplate sqlTemplate = new StringTemplate(executeDML);
		for (AbstractCompileOperator childOp : compileOp.getChildren()) {
			if (!this.splitOpIds.contains(childOp.getOperatorId())) {
				String childExecuteDML = this.genExecuteDML(childOp);

				// generate code: use "(...)" for sub-queries
				if (!childOp.isTable()) {
					childExecuteDML = AbstractToken.LBRACE + childExecuteDML
							+ AbstractToken.RBRACE;
				}
				args.put(childOp.getOperatorId().toString(), childExecuteDML);
			}
		}

		return sqlTemplate.toString(args);
	}

	/**
	 * Generate name for output table for given compile operator
	 * 
	 * @param compileOp
	 * @return
	 */
	private Identifier genOutputTableName(
			final AbstractCompileOperator compileOp) {
		return compileOp.getOperatorId().clone().append(OUT_PREFIX);
	}

	/**
	 * Generate name for output table for given compile operator and partition
	 * 
	 * @param compileOp
	 * @param partNum
	 * @return
	 */
	private Identifier genOutputTableName(
			final AbstractCompileOperator compileOp, final int partNum) {
		return compileOp.getOperatorId().clone().append(PART_PREFIX + partNum)
				.append(OUT_PREFIX);
	}

	/**
	 * Generate name for input table for given compile operator
	 * 
	 * @param compileOp
	 * @return
	 */
	private Identifier genInputTableName(final AbstractCompileOperator compileOp) {
		return compileOp.getOperatorId().clone();
	}

	/**
	 * Extract root operators of sub-plans in compile plan which are
	 * materialized (i.e., operators that have multiple consumers or that are
	 * explicitly marked as materialized)
	 * 
	 * @return
	 */
	private List<Identifier> extractSplitOps() {
		SplitPlanVisitor splitVisitor = new SplitPlanVisitor();
		for (AbstractCompileOperator root : this.compilePlan
				.getRootOps()) {
			splitVisitor.reset(root);
			splitVisitor.visit();
		}
		return splitVisitor.getSplitOpIds();
	}
}