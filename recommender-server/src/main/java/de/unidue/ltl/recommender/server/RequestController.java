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

package de.unidue.ltl.recommender.server;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import de.unidue.ltl.recommender.server.http.InceptionRequest;
import de.unidue.ltl.recommender.server.http.PredictionRequest;
import de.unidue.ltl.recommender.server.http.TrainingRequest;
import de.unidue.ltl.recommender.server.repository.Repository;
import de.unidue.ltl.recommender.server.tc.prediction.Predictor;
import de.unidue.ltl.recommender.server.train.InceptionRecommenderModel;
import de.unidue.ltl.recommender.server.train.Trainer;

@RestController
public class RequestController
{
    private Logger logger = LoggerFactory.getLogger(RequestController.class);

    @Autowired
    Repository repository;

    @Autowired
    Trainer trainer;
    
    @Autowired
    Predictor predictor;

    @RequestMapping(value = "/train", method = RequestMethod.POST)
    public ResponseEntity<String> executeTraining(@RequestBody TrainingRequest trainingRequest)
    {
        try {
            trainModel(trainingRequest.toInceptionRequest());
        }
        catch (Exception e) {
            logger.error("Error while training", e);
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    private InceptionRecommenderModel trainModel(InceptionRequest inceptionReq) throws Exception
    {
        InceptionRecommenderModel trainedModel = null;
        trainedModel = trainer.train(inceptionReq);
        repository.checkInModel(trainedModel, true);
        return trainedModel;
    }

    @RequestMapping(value = "/predict", method = RequestMethod.POST)
    public ResponseEntity<String> executePrediction(@RequestBody PredictionRequest predictionRequest)
    {
        try {
            String response = prediction(predictionRequest.toInceptionRequest());
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        catch (Exception e) {
            logger.error("Error while training", e);
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String prediction(InceptionRequest inceptionReq) throws Exception
    {
        InceptionRecommenderModel model = repository.getModel(inceptionReq.getLayer());
        predictor.predict(inceptionReq, model.getFileSystemLocation());
        return predictor.getResultsAsJson();
    }

    @ExceptionHandler
    void handleIllegalArgumentException(IllegalArgumentException e, HttpServletResponse response)
        throws IOException
    {
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }
}