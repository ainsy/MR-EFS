package org.apache.mahout.classifier.feature_selection.mapreduce;

import java.io.IOException;

import org.apache.commons.cli2.CommandLine;
import org.apache.commons.cli2.Group;
import org.apache.commons.cli2.Option;
import org.apache.commons.cli2.OptionException;
import org.apache.commons.cli2.builder.ArgumentBuilder;
import org.apache.commons.cli2.builder.DefaultOptionBuilder;
import org.apache.commons.cli2.builder.GroupBuilder;
import org.apache.commons.cli2.commandline.Parser;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.CommandLineUtil;
import org.apache.mahout.common.Pair;
import org.apache.mahout.classifier.feature_selection.builder.FSgenerator;
import org.apache.mahout.classifier.basic.data.Data;
import org.apache.mahout.classifier.basic.data.DataLoader;
import org.apache.mahout.classifier.basic.data.Dataset;
import org.apache.mahout.classifier.feature_selection.mapreduce.partial.PartialBuilder;
import org.apache.mahout.classifier.feature_selection.utils.PGUtils;
import org.apache.mahout.keel.Algorithms.Instance_Generation.Basic.*;
import org.apache.mahout.keel.Dataset.InstanceAttributes;
import org.apache.mahout.keel.Dataset.InstanceSet;
import org.apache.mahout.keel.Dataset.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Closeables;

public class FeatureSelectionModel extends Configured implements Tool {
  
  private static final Logger log = LoggerFactory.getLogger(FeatureSelectionModel.class);
  
  private Path dataPath;
  private Path datasetPath;
  private Path headerPath;
  private Path outputPath;
  private Path timePath;
  
  private String dataName;
  //private String timeName;
  private String algoritmoFS="CHC"; 
  private String reducePhase="Majority";

 // private boolean buildTimeIsStored = false;
  
  private long time;
//  private int nLabels; // Number of labels
  
 
  @Override
  public int run(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
    
	// Primero lectura de parámetros y control de que no falte:
	
    DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
    ArgumentBuilder abuilder = new ArgumentBuilder();
    GroupBuilder gbuilder = new GroupBuilder();
    
    Option dataOpt = obuilder.withLongName("data").withShortName("d").withRequired(true)
        .withArgument(abuilder.withName("path").withMinimum(1).withMaximum(1).create())
        .withDescription("Data path").create();
    
    Option datasetOpt = obuilder.withLongName("dataset").withShortName("ds").withRequired(true)
        .withArgument(abuilder.withName("dataset").withMinimum(1).withMaximum(1).create())
        .withDescription("The path of the file descriptor of the dataset").create();

    Option header = obuilder.withLongName("header").withShortName("he").withRequired(true)
            .withArgument(abuilder.withName("header").withMinimum(1).withMaximum(1).create())
            .withDescription("Header of the dataset in Keel format").create();
    
    Option outputOpt = obuilder.withLongName("output").withShortName("o").withRequired(true)
            .withArgument(abuilder.withName("path").withMinimum(1).withMaximum(1).create())
            .withDescription("Output path, will contain the set of selected features").create();
    
    Option pgMethod = obuilder.withLongName("fsMethod").withShortName("fs").withRequired(false)
            .withArgument(abuilder.withName("path").withMinimum(1).withMaximum(1).create())
            .withDescription("FS method: LVF. Default: LVF").create();
    
    Option reduceType = obuilder.withLongName("TypeOfReduce").withShortName("r").withRequired(false)
            .withArgument(abuilder.withName("path").withMinimum(1).withMaximum(1).create())
            .withDescription("Type of reduce: Majority. Default: Majority").create();
    
    
    Option helpOpt = obuilder.withLongName("help").withShortName("h")
        .withDescription("Print out help").create();
    
    Group group = gbuilder.withName("Options").withOption(dataOpt).withOption(datasetOpt).withOption(header).withOption(outputOpt).create();
    
    try {
      Parser parser = new Parser();
      parser.setGroup(group);
      CommandLine cmdLine = parser.parse(args);
      
      if (cmdLine.hasOption("help")) {
        CommandLineUtil.printHelp(group);
        return -1;
      }
      

      // Obtenemos los parámetros que nos interesen:
      
      dataName = cmdLine.getValue(dataOpt).toString();
      String datasetName = cmdLine.getValue(datasetOpt).toString();
      String outputName = cmdLine.getValue(outputOpt).toString();
      String headerName = cmdLine.getValue(header).toString();
      if (cmdLine.hasOption(pgMethod))
    	  this.algoritmoFS= cmdLine.getValue(pgMethod).toString();
      if (cmdLine.hasOption(reduceType))
    	  this.reducePhase= cmdLine.getValue(reduceType).toString();

      //nLabels = Integer.parseInt(cmdLine.getValue(labelsOpt).toString());      
      
      /*
      if (cmdLine.hasOption(timeOpt)) {
      	buildTimeIsStored = true;  
        timeName = cmdLine.getValue(timeOpt).toString();
      } 
       */
      if (log.isDebugEnabled()) {
        log.debug("data : {}", dataName);
        log.debug("dataset : {}", datasetName);
        log.debug("header : {}", header);
        log.debug("output : {}", outputName);
        log.debug("fsMethod : {}", algoritmoFS);
        log.debug("reduceType : {}", reducePhase);

        //log.debug("labels : {}", nLabels);
        //log.debug("time : {}", timeName);
      }

      dataPath = new Path(dataName);
      datasetPath = new Path(datasetName);
      outputPath = new Path(outputName);
      headerPath = new Path(headerName);

    } catch (OptionException e) {
      log.error("Exception", e);
      CommandLineUtil.printHelp(group);
      return -1;
    }
    
    buildModel();
    
    return 0;
  }
  

  private void buildModel() throws IOException, ClassNotFoundException, InterruptedException {
    // make sure the output path does not exist
    FileSystem ofs = outputPath.getFileSystem(getConf());


    
    if (ofs.exists(outputPath)) {
      log.error("Output path already exists");
      return;
    }

    FSgenerator fs_algorithm = new FSgenerator(algoritmoFS);
    // crear valores para los atributos en formato InstaceSEt y Prototypset
       
    
    FileSystem hfs = headerPath.getFileSystem(getConf());
    InstanceSet cabecera= PGUtils.readHeader(hfs, this.headerPath);
    
   
    // Here We refer to the mapper/reducer classes, establish the corresponding key and value classes.
    
    log.info("FS: Partial Mapred implementation"); 
    log.info("FS: Preprocessing the dataset...");
    
    Builder modelBuilder = new PartialBuilder(fs_algorithm, dataPath, datasetPath, reducePhase, getConf(), cabecera.getHeader());
    
    modelBuilder.setOutputDirName(outputPath.toString());
 
    
    time = System.currentTimeMillis();
    MapredOutput salida = modelBuilder.build();
    
    boolean []resultingSet = salida.getSelectedFeatures();
    int [] resultingPesos = salida.getPesos();
    
    time = System.currentTimeMillis() - time;
    log.info("FS: Build Time: {}", PGUtils.elapsedTime(time));
    log.info("FS: Build Time in seconds: {}", PGUtils.elapsedSeconds(time));
   
    // store the building time in a file to post-process:
	FileSystem outFS = outputPath.getFileSystem(getConf());
	Path filenamePath = new Path(outputPath, "BuildingTime").suffix(".txt");
	FSDataOutputStream ofile = null;

    ofile = outFS.create(filenamePath);
	ofile.writeUTF("\n"+PGUtils.elapsedSeconds(time));
	ofile.close();		
	
	filenamePath = new Path(outputPath, "seleccionadas").suffix(".txt");
   
	int cont=0;
	ofile = outFS.create(filenamePath);
	for(int i=0; i<resultingSet.length; i++){
		ofile.writeBoolean(resultingSet[i]);
		if(resultingSet[i]) cont++;
	}
	ofile.close();	
	
	System.out.println("Seleccionadas: "+cont+ " de "+resultingSet.length);
	
	filenamePath = new Path(outputPath, "pesos").suffix(".txt");

	ofile = outFS.create(filenamePath);
	for(int i=0; i<resultingPesos.length; i++){
		ofile.writeBytes(Integer.toString(resultingPesos[i])+"\n");
	
	}
	ofile.close();	
	
	

	

    
  }
  
  protected static Data loadData(Configuration conf, Path dataPath, Dataset dataset) throws IOException {
    log.info("FS: Loading the data...");
    FileSystem fs = dataPath.getFileSystem(conf);
    Data data = DataLoader.loadData(dataset, fs, dataPath);
    log.info("FS: Data Loaded");
    
    return data;
  }
  
  private void writeToFileBuildTime(String time) throws IOException{	
    FileSystem outFS = timePath.getFileSystem(getConf());
	FSDataOutputStream ofile = null;		
	Path filenamePath = new Path(timePath, dataName + "_build_time").suffix(".txt");
	try    
	  {	        	
        if (ofile == null) {
	      // this is the first value, it contains the name of the input file
	      ofile = outFS.create(filenamePath);
		  // write the Build Time	      	      	      	      
		  StringBuilder returnString = new StringBuilder(200);	      
	      returnString.append("=======================================================").append('\n');
		  returnString.append("Build Time\n");
		  returnString.append("-------------------------------------------------------").append('\n');
		  returnString.append(
		  StringUtils.rightPad(time,5)).append('\n');                  
		  returnString.append("-------------------------------------------------------").append('\n');	      				
		  String output = returnString.toString();
	      ofile.writeUTF(output);
		  ofile.close();		  
		} 	    
      } 
	finally 
      {
	    Closeables.closeQuietly(ofile);
	  }
  }
  
  public static void main(String[] args) throws Exception {
    ToolRunner.run(new Configuration(), new FeatureSelectionModel(), args);
  }
  
}
