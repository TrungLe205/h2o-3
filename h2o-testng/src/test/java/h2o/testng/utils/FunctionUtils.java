package h2o.testng.utils;

import h2o.testng.db.MySQL;
import hex.Distribution.Family;
import hex.Model;
import hex.ModelMetrics;
import hex.glm.GLM;
import hex.glm.GLMConfig;
import hex.glm.GLMModel;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Solver;
import hex.tree.drf.DRF;
import hex.tree.drf.DRFConfig;
import hex.tree.drf.DRFModel;
import hex.tree.gbm.GBM;
import hex.tree.gbm.GBMConfig;
import hex.tree.gbm.GBMModel;
import hex.tree.gbm.GBMModel.GBMParameters;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.testng.Assert;

import water.Key;
import water.Scope;
import water.TestNGUtil;
import water.fvec.FVecTest;
import water.fvec.Frame;
import water.parser.ParseDataset;

public class FunctionUtils {

	private final static String regexToSplitTestcase = ",";

	public final static String glm = "glm";
	public final static String gbm = "gbm";
	public final static String drf = "drf";

	public final static String smalldata = "smalldata";
	public final static String bigdata = "bigdata";

	// ---------------------------------------------- //
	// execute testcase
	// ---------------------------------------------- //
	public static String validate(Param[] params, String train_dataset_id, Dataset train_dataset,
			HashMap<String, String> rawInput) {

		System.out.println("Validate Parameters object with testcase: " + rawInput.get(CommonHeaders.testcase_id));
		String result = null;

		if (StringUtils.isEmpty(train_dataset_id)) {
			result = "In testcase, train dataset id is empty";
		}
		else if (train_dataset == null) {
			result = String.format("Dataset id %s do not have in dataset characteristic", train_dataset_id);
		}
		else if (!train_dataset.isAvailabel()) {
			result = "Dataset characteristic is not available";
		}
		else {
			result = Param.validateAutoSetParams(params, rawInput);
		}

		if (result != null) {
			result = "[INVALID] " + result;
		}

		return result;
	}

	/**
	 * Only use for DRF algorithm
	 * 
	 * @param tcHeaders
	 * @param input
	 * @return
	 */
	public static String checkImplemented(HashMap<String, String> rawInput) {

		System.out.println("check modelParameter object with testcase: " + rawInput.get(CommonHeaders.testcase_id));
		String result = null;

		if (StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_gaussian).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_binomial).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_multinomial).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_poisson).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_gamma).trim())
				|| StringUtils.isNotEmpty(rawInput.get(CommonHeaders.family_tweedie).trim())) {

			result = "Only AUTO family is implemented";
		}

		if (result != null) {
			result = "[NOT IMPL] " + result;
		}

		return result;
	}

	/**
	 * This function is only used for GLM algorithm.
	 * 
	 * @param tcHeaders
	 * @param rawInput
	 * @param trainFrame
	 * @param responseColumn
	 * @return null if testcase do not require betaconstraints otherwise return beta constraints frame
	 */
	private static Frame createBetaConstraints(HashMap<String, String> rawInput, Frame trainFrame, String responseColumn) {

		Frame betaConstraints = null;
		boolean isBetaConstraints = Param.parseBoolean(rawInput.get("betaConstraints"));
		String lowerBound = rawInput.get("lowerBound");
		String upperBound = rawInput.get("upperBound");

		if (!isBetaConstraints) {
			return null;
		}
		// Here's an example of how to make the beta constraints frame.
		// First, represent the beta constraints frame as a string, for example:

		// "names, lower_bounds, upper_bounds\n"+
		// "AGE, -.5, .5\n"+
		// "RACE, -.5, .5\n"+
		// "GLEASON, -.5, .5"
		String betaConstraintsString = "names, lower_bounds, upper_bounds\n";
		List<String> predictorNames = Arrays.asList(trainFrame._names);
		// predictorNames.remove(glmParams._response_column); // remove the response column name. we only want
		// predictors
		for (String name : predictorNames) {
			if (!name.equals(responseColumn)) {
				if (trainFrame.vec(name).isEnum()) { // need coefficient names for each level of a categorical
														// column
					for (String level : trainFrame.vec(name).domain()) {
						betaConstraintsString += String.format("%s.%s,%s,%s\n", name, level, lowerBound, upperBound);
					}
				}
				else { // numeric columns only need one coefficient name
					betaConstraintsString += String.format("%s,%s,%s\n", name, lowerBound, upperBound);
				}
			}
		}
		Key betaConsKey = Key.make("beta_constraints");
		FVecTest.makeByteVec(betaConsKey, betaConstraintsString);
		betaConstraints = ParseDataset.parse(Key.make("beta_constraints.hex"), betaConsKey);

		return betaConstraints;
	}

	public static Model.Parameters toModelParameter(Param[] params, String algorithm, String train_dataset_id,
			String validate_dataset_id, Dataset train_dataset, Dataset validate_dataset,
			HashMap<String, String> rawInput) {

		System.out.println("Create modelParameter object with testcase: " + rawInput.get(CommonHeaders.testcase_id));

		boolean isTunedValue = false;
		Model.Parameters modelParameter = null;

		switch (algorithm) {
			case FunctionUtils.drf:

				modelParameter = new DRFModel.DRFParameters();

				// set distribution param
				Family drfFamily = (Family) DRFConfig.familyParams.getValue(rawInput);

				if (drfFamily != null) {
					System.out.println("Set _distribution: " + drfFamily);
					modelParameter._distribution = drfFamily;
				}
				break;

			case FunctionUtils.glm:

				modelParameter = new GLMParameters();

				hex.glm.GLMModel.GLMParameters.Family glmFamily = (hex.glm.GLMModel.GLMParameters.Family) GLMConfig.familyOptionsParams
						.getValue(rawInput);
				Solver s = (Solver) GLMConfig.solverOptionsParams.getValue(rawInput);

				if (glmFamily != null) {
					System.out.println("Set _family: " + glmFamily);
					((GLMParameters) modelParameter)._family = glmFamily;
				}
				if (s != null) {
					System.out.println("Set _solver: " + s);
					((GLMParameters) modelParameter)._solver = s;
				}
				break;

			case FunctionUtils.gbm:

				modelParameter = new GBMParameters();

				// set distribution param
				Family gbmFamily = (Family) GBMConfig.familyParams.getValue(rawInput);

				if (gbmFamily != null) {
					System.out.println("Set _distribution: " + gbmFamily);
					modelParameter._distribution = gbmFamily;
				}
				break;

			default:
				System.out.println("can not parse to object parameter with algorithm: " + algorithm);
		}

		// set AutoSet params
		isTunedValue = Param.setAutoSetParams(modelParameter, params, rawInput);

		// It is got and saved to DB in MySQL class.
		if (isTunedValue) {
			rawInput.put(MySQL.tuned_or_defaults, MySQL.tuned);
		}
		else {
			rawInput.put(MySQL.tuned_or_defaults, MySQL.defaults);
		}

		// set response_column param
		modelParameter._response_column = train_dataset.getResponseColumn();

		// set train/validate params
		Frame trainFrame = null;
		Frame validateFrame = null;
		Frame betaConstraints = null;

		try {

			System.out.println("Create train frame: " + train_dataset_id);
			trainFrame = train_dataset.getFrame();

			if (StringUtils.isNotEmpty(train_dataset_id) && validate_dataset != null && validate_dataset.isAvailabel()) {
				System.out.println("Create validate frame: " + train_dataset_id);
				validateFrame = validate_dataset.getFrame();
			}

			if (algorithm.equals(FunctionUtils.glm)) {
				betaConstraints = createBetaConstraints(rawInput, trainFrame, train_dataset.getResponseColumn());
				if (betaConstraints != null) {
					((GLMParameters) modelParameter)._beta_constraints = betaConstraints._key;
				}
			}
		}
		catch (Exception e) {
			if (trainFrame != null) {
				trainFrame.remove();
			}
			if (validateFrame != null) {
				validateFrame.remove();
			}
			if (betaConstraints != null) {
				betaConstraints.remove();
			}
			throw e;
		}

		System.out.println("Set train frame");
		modelParameter._train = trainFrame._key;

		if (validateFrame != null) {
			System.out.println("Set validate frame");
			modelParameter._valid = validateFrame._key;
		}

		System.out.println("Create success modelParameter object.");
		return modelParameter;
	}

	public static void basicTesting(String algorithm, Model.Parameters parameter, boolean isNegativeTestcase,
			HashMap<String, String> rawInput) {

		Frame trainFrame = null;
		Frame score = null;

		DRF drfJob = null;
		DRFModel drfModel = null;
		ModelMetrics modelMetrics = null;

		Key modelKey = Key.make("model");
		GLM glmJob = null;
		GLMModel glmModel = null;
		HashMap<String, Double> coef = null;

		GBM gbmJob = null;
		GBMModel gbmModel = null;

		trainFrame = parameter._train.get();

		try {
			Scope.enter();
			switch (algorithm) {
				case FunctionUtils.drf:

					System.out.println("Build model");
					drfJob = new DRF((DRFModel.DRFParameters) parameter);

					System.out.println("Train model:");
					drfModel = drfJob.trainModel().get();

					System.out.println("Predict testcase");
					score = drfModel.score(trainFrame);

					modelMetrics = drfModel._output._training_metrics;

					break;

				case FunctionUtils.glm:

					System.out.println("Build model");
					glmJob = new GLM(modelKey, "basic glm test", (GLMParameters) parameter);

					System.out.println("Train model");
					glmModel = glmJob.trainModel().get();

					coef = glmModel.coefficients();

					System.out.println("Predict testcase ");
					score = glmModel.score(trainFrame);

					modelMetrics = glmModel._output._training_metrics;
					break;

				case FunctionUtils.gbm:

					System.out.println("Build model ");
					gbmJob = new GBM((GBMParameters) parameter);

					System.out.println("Train model");
					gbmModel = gbmJob.trainModel().get();

					System.out.println("Predict testcase ");
					score = gbmModel.score(trainFrame);

					modelMetrics = gbmModel._output._training_metrics;
					break;
			}

			if (isNegativeTestcase) {
				Assert.fail("It is negative testcase");
			}
			else {
				System.out.println("Testcase passed.");
				System.out.println("MSE: " + modelMetrics._MSE);
				if (modelMetrics.auc() != null) {
					System.out.println("AUC: " + modelMetrics.auc()._auc);
				}
				else {
					System.out.println("AUC: NA");
				}
			}

			// write into db
			MySQL.save("MSE", String.valueOf(modelMetrics._MSE), rawInput);
			if (modelMetrics.auc() != null) {
				MySQL.save("AUC", String.valueOf(modelMetrics.auc()._auc), rawInput);
			}
		}
		catch (Exception ex) {

			System.out.println("Testcase failed");
			ex.printStackTrace();
			if (!isNegativeTestcase) {
				Assert.fail("Testcase failed", ex);
			}
			else {
				System.out.println("This is negative testcase");
			}
		}
		catch (AssertionError ae) {

			System.out.println("Testcase failed");
			ae.printStackTrace();
			if (!isNegativeTestcase) {
				Assert.fail("Testcase failed", ae);
			}
			else {
				System.out.println("This is negative testcase");
			}
		}
		finally {
			if (drfJob != null) {
				drfJob.remove();
			}
			if (drfModel != null) {
				drfModel.delete();
			}
			if (glmJob != null) {
				glmJob.remove();
			}
			if (glmModel != null) {
				glmModel.delete();
			}
			if (gbmJob != null) {
				gbmJob.remove();
			}
			if (gbmModel != null) {
				gbmModel.delete();
			}
			if (score != null) {
				score.remove();
				score.delete();
			}
			Scope.exit();
		}
	}

	// ---------------------------------------------- //
	// initiate data. Read testcase files
	// ---------------------------------------------- //
	public static Object[][] readAllTestcase(HashMap<String, Dataset> dataSetCharacteristic, String algorithm) {

		if (StringUtils.isNotEmpty(algorithm)) {
			return readAllTestcaseOneAlgorithm(dataSetCharacteristic, algorithm);
		}
		Object[][] result = null;
		int nrows = 0;
		int ncols = 0;
		int r = 0;
		int i = 0;

		Object[][] drfTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.drf);
		Object[][] gbmTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.gbm);
		Object[][] glmTestcase = readAllTestcaseOneAlgorithm(dataSetCharacteristic, FunctionUtils.glm);

		if (drfTestcase != null && drfTestcase.length != 0) {
			nrows = drfTestcase[0].length;
			ncols += drfTestcase.length;
		}
		if (gbmTestcase != null && gbmTestcase.length != 0) {
			nrows = gbmTestcase[0].length;
			ncols += gbmTestcase.length;
		}
		if (glmTestcase != null && glmTestcase.length != 0) {
			nrows = glmTestcase[0].length;
			ncols += glmTestcase.length;
		}

		result = new Object[ncols][nrows];

		if (drfTestcase != null && drfTestcase.length != 0) {
			for (i = 0; i < drfTestcase.length; i++) {
				result[r++] = drfTestcase[i];
			}
		}
		if (gbmTestcase != null && gbmTestcase.length != 0) {
			for (i = 0; i < gbmTestcase.length; i++) {
				result[r++] = gbmTestcase[i];
			}
		}
		if (glmTestcase != null && glmTestcase.length != 0) {
			for (i = 0; i < glmTestcase.length; i++) {
				result[r++] = glmTestcase[i];
			}
		}

		return result;
	}

	private static Object[][] readAllTestcaseOneAlgorithm(HashMap<String, Dataset> dataSetCharacteristic,
			String algorithm) {

		int indexRowHeader = 0;
		String positiveTestcaseFilePath = null;
		String negativeTestcaseFilePath = null;
		List<String> listHeaders = null;

		switch (algorithm) {
			case FunctionUtils.drf:
				indexRowHeader = DRFConfig.indexRowHeader;
				positiveTestcaseFilePath = DRFConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = DRFConfig.negativeTestcaseFilePath;
				listHeaders = DRFConfig.listHeaders;
				break;

			case FunctionUtils.glm:
				indexRowHeader = GLMConfig.indexRowHeader;
				positiveTestcaseFilePath = GLMConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = GLMConfig.negativeTestcaseFilePath;
				listHeaders = GLMConfig.listHeaders;
				break;

			case FunctionUtils.gbm:
				indexRowHeader = GBMConfig.indexRowHeader;
				positiveTestcaseFilePath = GBMConfig.positiveTestcaseFilePath;
				negativeTestcaseFilePath = GBMConfig.negativeTestcaseFilePath;
				listHeaders = GBMConfig.listHeaders;
				break;

			default:
				System.out.println("do not implement for algorithm: " + algorithm);
				return null;
		}

		Object[][] result = FunctionUtils.dataProvider(dataSetCharacteristic, listHeaders, algorithm,
				positiveTestcaseFilePath, negativeTestcaseFilePath, indexRowHeader);

		return result;
	}

	private static Object[][] dataProvider(HashMap<String, Dataset> dataSetCharacteristic, List<String> listHeaders,
			String algorithm, String positiveTestcaseFilePath, String negativeTestcaseFilePath, int indexRowHeader) {

		Object[][] result = null;
		Object[][] positiveData = null;
		Object[][] negativeData = null;
		int numRow = 0;
		int numCol = 0;
		int r = 0;

		positiveData = readTestcaseFile(dataSetCharacteristic, listHeaders, positiveTestcaseFilePath, indexRowHeader,
				algorithm, false);
		negativeData = readTestcaseFile(dataSetCharacteristic, listHeaders, negativeTestcaseFilePath, indexRowHeader,
				algorithm, true);

		if (positiveData != null && positiveData.length != 0) {
			numRow += positiveData.length;
			numCol = positiveData[0].length;
		}
		if (negativeData != null && negativeData.length != 0) {
			numRow += negativeData.length;
			numCol = negativeData[0].length;
		}

		if (numRow == 0) {
			return null;
		}

		result = new Object[numRow][numCol];

		if (positiveData != null && positiveData.length != 0) {
			for (int i = 0; i < positiveData.length; i++) {
				result[r++] = positiveData[i];
			}
		}
		if (negativeData != null && negativeData.length != 0) {
			for (int i = 0; i < negativeData.length; i++) {
				result[r++] = negativeData[i];
			}
		}

		return result;
	}

	private static Object[][] readTestcaseFile(HashMap<String, Dataset> dataSetCharacteristic,
			List<String> listHeaders, String fileName, int indexRowHeader, String algorithm, boolean isNegativeTestcase) {

		Object[][] result = null;
		List<String> lines = null;
		String[] hearderRow = null;

		if (StringUtils.isEmpty(fileName)) {
			System.out.println("Not found file: " + fileName);
			return null;
		}

		try {
			// read data from file
			lines = Files.readAllLines(TestNGUtil.find_test_file_static(fileName).toPath(), Charset.defaultCharset());
		}
		catch (Exception ignore) {
			System.out.println("Cannot open file: " + fileName);
			ignore.printStackTrace();
			return null;
		}

		// remove headers
		lines.removeAll(lines.subList(0, indexRowHeader - 1));

		hearderRow = lines.remove(0).split(regexToSplitTestcase, -1);

		if (!validateTestcaseFile(listHeaders, fileName, hearderRow)) {
			System.out.println("Testcase file is wrong format");
			return null;
		}

		result = new Object[lines.size()][9];
		int r = 0;
		for (String line : lines) {
			String[] variables = line.trim().split(regexToSplitTestcase, -1);
			HashMap<String, String> rawInput = parseToHashMap(listHeaders, hearderRow, variables);

			result[r][0] = rawInput.get(CommonHeaders.testcase_id);
			result[r][1] = rawInput.get(CommonHeaders.test_description);
			result[r][2] = rawInput.get(CommonHeaders.train_dataset_id);
			result[r][3] = rawInput.get(CommonHeaders.validate_dataset_id);
			result[r][4] = dataSetCharacteristic.get(result[r][2]);
			result[r][5] = dataSetCharacteristic.get(result[r][3]);
			result[r][6] = algorithm;
			result[r][7] = isNegativeTestcase;
			result[r][8] = rawInput;

			r++;
		}

		return result;
	}

	private static boolean validateTestcaseFile(List<String> listHeaders, String fileName, String[] headerRow) {

		System.out.println("validate file: " + fileName);

		for (String header : listHeaders) {
			if (Arrays.asList(headerRow).indexOf(header) < 0) {
				System.out.println(String.format("find not found %s column in %s", header, fileName));
				return false;
			}

		}

		return true;
	}

	private static HashMap<String, String> parseToHashMap(List<String> listHeaders, String[] headerRow,
			String[] testcaseRow) {

		HashMap<String, String> result = new HashMap<String, String>();

		for (String header : listHeaders) {
			result.put(header, testcaseRow[Arrays.asList(headerRow).indexOf(header)]);
		}

		return result;
	}

	public static Object[][] removeAllTestcase(Object[][] testcases, String size) {

		if (testcases == null || testcases.length == 0) {
			return null;
		}

		if (StringUtils.isEmpty(size)) {
			return testcases;
		}

		Object[][] result = null;
		Object[][] temp = null;
		int nrows = 0;
		int ncols = 0;
		int r = 0;
		int i = 0;
		Dataset dataset = null;

		ncols = testcases.length;
		nrows = testcases[0].length;
		temp = new Object[ncols][nrows];

		for (i = 0; i < ncols; i++) {

			dataset = (Dataset) testcases[i][4];

			if (dataset == null) {
				// because we have to show any INVALID testcase thus we have to add this testcase
				temp[r++] = testcases[i];
			}
			else if (size.equals(dataset.getDataSetDirectory())) {
				temp[r++] = testcases[i];
			}
		}

		if (r == 0) {
			System.out.println(String.format("dataset characteristic have no size what is: %s.", size));
		}
		else {

			result = new Object[r][nrows];

			for (i = 0; i < r; i++) {
				result[i] = temp[i];
			}
		}

		return result;
	}

	// ---------------------------------------------- //
	// management dataset characteristic file
	// ---------------------------------------------- //
	public static HashMap<String, Dataset> readDataSetCharacteristic() {

		HashMap<String, Dataset> result = new HashMap<String, Dataset>();
		final String dataSetCharacteristicFilePath = "h2o-testng/src/test/resources/datasetCharacteristics.csv";

		final int numCols = 6;
		final int dataSetId = 0;
		final int dataSetDirectory = 1;
		final int fileName = 2;
		final int responseColumn = 3;
		final int columnNames = 4;
		final int columnTypes = 5;

		File file = null;
		List<String> lines = null;

		try {
			System.out.println("read dataset characteristic");
			file = TestNGUtil.find_test_file_static(dataSetCharacteristicFilePath);
			lines = Files.readAllLines(file.toPath(), Charset.defaultCharset());
		}
		catch (Exception e) {
			System.out.println("Cannot open dataset characteristic file: " + dataSetCharacteristicFilePath);
			e.printStackTrace();
		}

		for (String line : lines) {
			System.out.println("read line: " + line);
			String[] arr = line.trim().split(",", -1);

			if (arr.length < numCols) {
				System.out.println("length of line is short");
			}
			else {
				System.out.println("parse to DataSet object");
				Dataset dataset = new Dataset(arr[dataSetId], arr[dataSetDirectory], arr[fileName],
						arr[responseColumn], arr[columnNames], arr[columnTypes]);

				result.put(arr[dataSetId], dataset);
			}
		}

		return result;
	}

	public static void removeDatasetInDatasetCharacteristic(HashMap<String, Dataset> mapDatasetCharacteristic,
			String dataSetDirectory) {

		if (StringUtils.isEmpty(dataSetDirectory)) {
			return;
		}

		Dataset temp = null;
		for (String key : mapDatasetCharacteristic.keySet()) {

			temp = mapDatasetCharacteristic.get(key);
			if (temp != null && dataSetDirectory.equals(temp.getDataSetDirectory())) {
				temp.closeFrame();
				mapDatasetCharacteristic.remove(key);
			}
		}
	}

	public static void closeAllFrameInDatasetCharacteristic(HashMap<String, Dataset> mapDatasetCharacteristic) {

		for (String key : mapDatasetCharacteristic.keySet()) {

			mapDatasetCharacteristic.get(key).closeFrame();
		}
	}
}