/*******************************************************************************
 * Copyright 2018
 * Language Technology Lab
 * University of Duisburg-Essen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package de.unidue.ltl.recommender.core.train;

import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;
import static org.apache.uima.fit.factory.CollectionReaderFactory.createReaderDescription;
import static org.dkpro.tc.api.features.TcFeatureFactory.create;

import java.io.File;

import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.dkpro.tc.features.ngram.CharacterNGram;
import org.dkpro.tc.features.tcu.TargetSurfaceFormContextFeature;
import org.dkpro.tc.ml.builder.FeatureMode;
import org.dkpro.tc.ml.builder.LearningMode;
import org.dkpro.tc.ml.builder.MLBackend;
import org.dkpro.tc.ml.crfsuite.CrfSuiteAdapter;
import org.dkpro.tc.ml.experiment.builder.ExperimentBuilder;
import org.dkpro.tc.ml.experiment.builder.ExperimentType;

import de.tudarmstadt.ukp.dkpro.core.io.bincas.BinaryCasReader;
import de.unidue.ltl.recommender.core.DKProTcSkeleton;
import de.unidue.ltl.recommender.core.train.report.CleanUpReport;

public class TrainNewModel
        extends DKProTcSkeleton {

    public TrainNewModel() throws Exception {
        super();
    }

    public void run(String[] cas, String typesystem, String annotationName,
                    String annotationFieldName, File targetFolder, String anchorMode)
            throws Exception {
        dkproHome();

        TypeSystemDescription typeSystem = prepare(cas, typesystem);
        logger.debug("Created typesystem");

        startTraining(binCasInputFolder, typeSystem, targetFolder, annotationName,
                annotationFieldName, anchorMode);
        logger.debug("Training finished");

        cleanUp();
    }

    private static void startTraining(File casPredictOutput, TypeSystemDescription typeSystem,
            File targetFolder, String annotationName, String annotationFieldName, String anchorMode)
            throws Exception {

        CollectionReaderDescription trainReader = createReaderDescription(
                BinaryCasReader.class,
                typeSystem,
                BinaryCasReader.PARAM_LANGUAGE, "x-undefined",
                BinaryCasReader.PARAM_SOURCE_LOCATION, casPredictOutput.getAbsolutePath(),
                BinaryCasReader.PARAM_PATTERNS, "*.bin",
                BinaryCasReader.PARAM_OVERRIDE_DOCUMENT_METADATA, true
        );

        ExperimentBuilder builder = new ExperimentBuilder();
        builder.experiment(ExperimentType.SAVE_MODEL, "InceptionTrain")
                .dataReaderTrain(trainReader)
                .featureMode(FeatureMode.SEQUENCE)
                .learningMode(LearningMode.SINGLE_LABEL)
                .outputFolder(targetFolder.getAbsolutePath())
                .reports(new CleanUpReport())
                .machineLearningBackend(
                        new MLBackend(new CrfSuiteAdapter(),
                                CrfSuiteAdapter.ALGORITHM_ADAPTIVE_REGULARIZATION_OF_WEIGHT_VECTOR))
                .preprocessing(
                        getModeDependentTargetDefiner(anchorMode, annotationName, annotationFieldName)
                        )
                .features(create(TargetSurfaceFormContextFeature.class,
                        TargetSurfaceFormContextFeature.PARAM_RELATIVE_TARGET_ANNOTATION_INDEX, -2)
                        , create(TargetSurfaceFormContextFeature.class,
                                TargetSurfaceFormContextFeature.PARAM_RELATIVE_TARGET_ANNOTATION_INDEX, -1)
                        , create(TargetSurfaceFormContextFeature.class,
                                TargetSurfaceFormContextFeature.PARAM_RELATIVE_TARGET_ANNOTATION_INDEX, 0)
                        , create(CharacterNGram.class,
                                CharacterNGram.PARAM_NGRAM_USE_TOP_K, 2500,
                                CharacterNGram.PARAM_NGRAM_LOWER_CASE, true,
                                CharacterNGram.PARAM_NGRAM_MIN_N, 2,
                                CharacterNGram.PARAM_NGRAM_MAX_N, 4)
                )
                .run();

    }

    private static AnalysisEngineDescription getModeDependentTargetDefiner(String anchoringMode,
            String annotationName, String annotationFieldName) throws ResourceInitializationException
    {
        if (anchoringMode.equals("singleToken")) {
            return createEngineDescription(SingleTokenLevelTrainingOutcomeAnnotator.class,
                    SingleTokenLevelTrainingOutcomeAnnotator.PARAM_ANNOTATION_TARGET_NAME,
                    annotationName,
                    SingleTokenLevelTrainingOutcomeAnnotator.PARAM_ANNOTATION_TARGET_FIELD_NAME,
                    annotationFieldName);
        }
        else if (anchoringMode.equals("tokens")) {
            return createEngineDescription(MultipleTokenSpanLevelTrainingOutcomeAnnotator.class,
                    MultipleTokenSpanLevelTrainingOutcomeAnnotator.PARAM_ANNOTATION_TARGET_NAME,
                    annotationName,
                    MultipleTokenSpanLevelTrainingOutcomeAnnotator.PARAM_ANNOTATION_TARGET_FIELD_NAME,
                    annotationFieldName);
        }
       
        
        throw new IllegalStateException("Anchoring mode ["+anchoringMode+"] is not known - don't know what to do - failing");
    }
}
