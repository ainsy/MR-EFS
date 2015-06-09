package org.apache.mahout.classifier.feature_selection.mapreduce;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;


import com.google.common.io.Closeables;
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
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.CommandLineUtil;
import org.apache.mahout.common.commandline.DefaultOptionCreator;
import org.apache.mahout.classifier.ResultAnalyzer;
import org.apache.mahout.classifier.ClassifierResult;
import org.apache.mahout.classifier.pg.data.Dataset;
import org.apache.mahout.classifier.pg.mapreduce.KNNClassifier;
import org.apache.mahout.classifier.pg.utils.PGUtils;
import org.apache.mahout.keel.Algorithms.Instance_Generation.Basic.PrototypeSet;
import org.apache.mahout.keel.Dataset.InstanceSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool to classify a Dataset using a previously preprocessed data set
 */
public class FSconstructor extends Configured implements Tool{
  
  private static final Logger log = LoggerFactory.getLogger(FSconstructor.class);

  private FileSystem dataTST_FS;
  private Path dataPath; // input data path
  private Path datasetPath; // info path
  private Path headerPath; // .header path

  private Path fsPath; // path where the model is stored
  private FileSystem outFS;
  private Path outputPath; // path to predictions file, if null do not output the predictions
  private String dataName;
  private long time;
  
  private int previousMaps=0;
  private double percMajority=0;
	  
  @Override
  public int run(String[] args) throws IOException, ClassNotFoundException, InterruptedException {
  // TODO Auto-generated method stub
    DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
    ArgumentBuilder abuilder = new ArgumentBuilder();
    GroupBuilder gbuilder = new GroupBuilder();

    // -i option, data set file .arff
    Option inputOpt = DefaultOptionCreator.inputOption().create();

    // -ds option, data set file descriptor .info
    Option testOpt = obuilder.withLongName("info").withShortName("ds").withRequired(true).withArgument(
	        abuilder.withName("test").withMinimum(1).withMaximum(1).create()).
	        withDescription("The path of the file descriptor of the dataset").create();
    
    Option header = obuilder.withLongName("header").withShortName("he").withRequired(true)
            .withArgument(abuilder.withName("header").withMinimum(1).withMaximum(1).create())
            .withDescription("Header of the dataset in Keel format").create();
    
    Option preprocessedOpt = obuilder.withLongName("feature_selection").withShortName("fs").withRequired(true).withArgument(
	        abuilder.withName("path").withMinimum(1).withMaximum(1).create()).
	        withDescription("Feature selection path").create();
    
    Option PreviousMaps = obuilder.withLongName("num_maps").withShortName("maps").withRequired(false).withArgument(
	        abuilder.withName("path").withMinimum(1).withMaximum(1).create()).
	        withDescription("NumMaps used in the FS process path").create();

    Option percMajority = obuilder.withLongName("majority").withShortName("perc").withRequired(false).withArgument(
	        abuilder.withName("path").withMinimum(1).withMaximum(1).create()).
	        withDescription("NumMaps used in the FS process path").create();
    
    

	Option outputOpt = DefaultOptionCreator.outputOption().create();

	Option helpOpt = DefaultOptionCreator.helpOption();

	Group group = gbuilder.withName("Options").withOption(inputOpt).withOption(testOpt).withOption(header).withOption(preprocessedOpt).withOption(PreviousMaps).withOption(percMajority)
	        .withOption(outputOpt).withOption(helpOpt).create(); 

	try {
	  Parser parser = new Parser();
	  parser.setGroup(group);
	  CommandLine cmdLine = parser.parse(args);

	  if (cmdLine.hasOption("help")) {
	    CommandLineUtil.printHelp(group);
	    return -1;
	  }

	  dataName = cmdLine.getValue(inputOpt).toString();
	  String datasetName = cmdLine.getValue(testOpt).toString();
	  String fsName = cmdLine.getValue(preprocessedOpt).toString();
	  String outputName = cmdLine.hasOption(outputOpt) ? cmdLine.getValue(outputOpt).toString() : null;
      String headerName = cmdLine.getValue(header).toString();
      
      if(PreviousMaps !=null){
    	  this.previousMaps = Integer.parseInt(cmdLine.getValue(PreviousMaps).toString());

    	  // it is also supossed to be fullied the majority.
    	  this.percMajority = Double.parseDouble(cmdLine.getValue(percMajority).toString());

      }

	  if (log.isDebugEnabled()) {
		log.debug("input   : {}", dataName);
	    log.debug("dataset   : {}", datasetName);
        log.debug("header : {}", header);
	    log.debug("preprocessed     : {}", fsName);
	    log.debug("output    : {}", outputName);
	  }

	  dataPath = new Path(dataName);
	  datasetPath = new Path(datasetName);
	  fsPath = new Path(fsName);
      headerPath = new Path(headerName);

	  if (outputName != null) {
	    outputPath = new Path(outputName);
	  }
	  
	} catch (OptionException e) {
	  
      log.warn(e.toString(), e);
	  CommandLineUtil.printHelp(group);
	  return -1;
	  
	}
	    
	time = System.currentTimeMillis();
	    
	testPreprocessedSet();
	    
	time = System.currentTimeMillis() - time;
	    
	//writeToFileClassifyTime(PGUtils.elapsedTime(time));
	writeToFileClassifyTime("\n"+PGUtils.elapsedSeconds(time));

    return 0;
  }
  
  private void testPreprocessedSet() throws IOException, ClassNotFoundException, InterruptedException {
	  
	  log.info("Initializing process");
	// make sure the output file does not exist
	if (outputPath != null) {
	  outFS = outputPath.getFileSystem(getConf());
	  if (outFS.exists(outputPath)) {
	    throw new IllegalArgumentException("Output path already exists");
	  }
	}
	
	// make sure the test data exists
    dataTST_FS = dataPath.getFileSystem(getConf());
    if (!dataTST_FS.exists(dataPath)) {
      throw new IllegalArgumentException("The Test data path does not exist");
    }
    
	// make sure the preprocessedPath exists
    FileSystem mfs = fsPath.getFileSystem(getConf());
    if (!mfs.exists(fsPath)) {
      throw new IllegalArgumentException("The fsPath does not exist");
    }
    
    if (outputPath == null) {
      throw new IllegalArgumentException("You must specify the ouputPath when using the mapreduce implementation");
    }
    
    // orden: dataset, info, RS, salida
   // PrototypeSet RS=PGUtils.readPrototypeSet(preprocessedPath.toString());
    
    FileSystem hfs = headerPath.getFileSystem(getConf());
    InstanceSet cabecera=PGUtils.readHeader(hfs, this.headerPath);
    
    //PGUtils.readPrototypeSet(this.headerPath.toString());

    if(this.previousMaps==0){
    
    	FeatureSeparator fsSeparator = new FeatureSeparator(dataPath, datasetPath, fsPath, outputPath, getConf(), cabecera.getHeader());

    	fsSeparator.run();
    }else{
    	
    	int numOfSelectedFeatures = (int) (this.previousMaps*this.percMajority);
    	System.out.println(numOfSelectedFeatures);
    	FeatureSeparator fsSeparator = new FeatureSeparator(dataPath, datasetPath, fsPath, outputPath, getConf(), cabecera.getHeader(), numOfSelectedFeatures);

    	fsSeparator.run();
    }
    
    /*
    String newHeader=fsSeparator.getNewHeader();
    
    Path filenamePath = new Path(outputPath, dataPath.getName()).suffix(".header");
	outFS = outputPath.getFileSystem(getConf());
	FSDataOutputStream ofile = null;

    ofile = outFS.create(filenamePath);

    ofile.writeBytes(newHeader);
	ofile.close();		  

 */
  }
  
  private void writePredictions(double results[][]) throws IOException {
	    NumberFormat decimalFormatter = new DecimalFormat("0.########");
		outFS = outputPath.getFileSystem(getConf());
		FSDataOutputStream ofile = null;
		Path filenamePath = new Path(outputPath, "Predictions").suffix(".txt");
	    try    
	    {	        	
	      if (ofile == null) {
		    // this is the first value, it contains the name of the input file
		    ofile = outFS.create(filenamePath);
			// write the Confusion Matrix	      	      	      	      
			StringBuilder returnString = new StringBuilder();	
			
	
		    for (double[] res : results) {
		    	 // returnString.append(res[1]+"\n");
		    	String dato = Double.toString(res[1])+"\n";
				ofile.writeBytes(dato);

		    }
		    
			ofile.close();		  
	      } 	    
		} 
	    finally 
	    {
	      Closeables.closeQuietly(ofile);
	    }
	  } 
  
  private void parseOutput(ResultAnalyzer analyzer, double accuracy) throws IOException {
    NumberFormat decimalFormatter = new DecimalFormat("0.########");
	outFS = outputPath.getFileSystem(getConf());
	FSDataOutputStream ofile = null;
	//int pos=dataName.indexOf('t');
    // String subStr=dataName.substring(0, pos);
	//Path filenamePath = new Path(outputPath, subStr + "_confusion_matrix").suffix(".txt");
	Path filenamePath = new Path(outputPath, "Confusion_matrix").suffix(".txt");
    try    
    {	        	
      if (ofile == null) {
	    // this is the first value, it contains the name of the input file
	    ofile = outFS.create(filenamePath);
		// write the Confusion Matrix	      	      	      	      
		StringBuilder returnString = new StringBuilder();	
		

		returnString.append("=======================================================").append('\n');
		returnString.append("Confusion Matrix\n");
		returnString.append("-------------------------------------------------------").append('\n');
		
		
    	int [][] matrix = analyzer.getConfusionMatrix().getConfusionMatrix();	      
		for(int i=0; i< matrix.length-1; i++){
		  for(int j=0; j< matrix[i].length-1; j++){	          	          
		    returnString.append(StringUtils.rightPad(Integer.toString(matrix[i][j]), 5)).append('\t');	
		  } 	        
		  returnString.append('\n');
		}
		
		
		returnString.append("-------------------------------------------------------\n\n").append('\n');
		returnString.append("\nAccuracy: "+accuracy).append('\n');
		returnString.append("-------------------------------------------------------").append('\n');

		
		
		returnString.append("-------------------------------------------------------").append('\n');	      	      
		returnString.append("AUC - Area Under the Curve ROC\n");
		returnString.append(StringUtils.rightPad(decimalFormatter.format(computeAuc(matrix)), 5)).append('\n');                  
		returnString.append("-------------------------------------------------------").append('\n');	      
		returnString.append("GM - Geometric Mean\n");
		returnString.append(StringUtils.rightPad(decimalFormatter.format(computeGM(matrix)), 5)).append('\n');                  
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
	 
  private double computeAcc(int [][] matrix){
	  
	  return 0;
  }
  private double computeAuc(int [][] matrix){
    int [] classesDistribution = new int [matrix.length-1];  
	for(int i=0; i< matrix.length-1; i++){
      for(int j=0; j< matrix[i].length-1; j++){	          	          
	    classesDistribution[i]+=matrix[i][j];	
	  } 	        	   
	}    
	int posClassId = 0;
	int posNumInstances = classesDistribution[0]; 
	for (int k=1; k<matrix.length-1; k++) {
	  if (classesDistribution[k] < posNumInstances) {
	    posClassId = k;
	 	posNumInstances = classesDistribution[k];
	   }
	}
	double tp_rate = 0.0;
	double fp_rate = 0.0;
	if(posClassId == 0){
	  tp_rate=((double)matrix[0][0]/(matrix[0][0]+matrix[0][1]));	
	  fp_rate=((double)matrix[1][0]/(matrix[1][0]+matrix[1][1]));
	}
	else{
	  fp_rate=((double)matrix[0][1]/(matrix[0][1]+matrix[0][0]));	
	  tp_rate=((double)matrix[1][1]/(matrix[1][1]+matrix[1][0]));	
	}
	return ((1+tp_rate-fp_rate)/2);
  }
	  
  private double computeGM(int [][] matrix){
    int [] classesDistribution = new int [matrix.length-1];  
	for(int i=0; i< matrix.length-1; i++){
	  for(int j=0; j< matrix[i].length-1; j++){	          	          
	    classesDistribution[i]+=matrix[i][j];	
	  } 	        	   
	}    
	int posClassId = 0;
	int posNumInstances = classesDistribution[0]; 
	for (int k=1; k<matrix.length-1; k++) {
	  if (classesDistribution[k] < posNumInstances) {
	    posClassId = k;
		posNumInstances = classesDistribution[k];
	  }
	}
	double sensisivity = 0.0;
	double specificity = 0.0;
	if(posClassId == 0){
	  sensisivity=((double)matrix[0][0]/(matrix[0][0]+matrix[0][1]));	
	  specificity=((double)matrix[1][1]/(matrix[1][1]+matrix[1][0]));
	}
	else{
      specificity=((double)matrix[0][0]/(matrix[0][0]+matrix[0][1]));	
	  sensisivity=((double)matrix[1][1]/(matrix[1][1]+matrix[1][0]));	
    }
	return (Math.sqrt(sensisivity*specificity));  
  }
  
  private void writeToFileClassifyTime(String time) throws IOException{	
    FileSystem outFS = outputPath.getFileSystem(getConf());
	FSDataOutputStream ofile = null;		
	//Path filenamePath = new Path(outputPath, dataName + "_classify_time").suffix(".txt");
	Path filenamePath = new Path(outputPath, "Classification_time").suffix(".txt");
	try    
	{	        	
      if (ofile == null) {
	    // this is the first value, it contains the name of the input file
	    ofile = outFS.create(filenamePath);
	    // write the Classify Time	      	      	      	      
		StringBuilder returnString = new StringBuilder(200);	      
		
		/*returnString.append("=======================================================").append('\n');
		returnString.append("Classification Time\n");
		returnString.append("-------------------------------------------------------").append('\n');
		returnString.append(
			    		  StringUtils.rightPad(time,5)).append('\n');                  
		returnString.append("-------------------------------------------------------").append('\n');
		*/
		
		returnString.append(time);
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
    ToolRunner.run(new Configuration(), new FSconstructor(), args);
  }
}
