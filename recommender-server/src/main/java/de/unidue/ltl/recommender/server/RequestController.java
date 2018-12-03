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
import java.util.Arrays;
import java.util.concurrent.Semaphore;

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

    Semaphore trainingRunning = new Semaphore(1);
    /*
     * controls access to the model repository to avoid that a model is read of which a new version
     * is written in the same moment. We do not keep explicitly track of which model is requested;
     * we just block all read accesses when a model update is in progress
     */
    Semaphore readModelRepPermitted = new Semaphore(1);

    @RequestMapping(value = "/train", method = RequestMethod.POST)
    public ResponseEntity<String> executeTraining(@RequestBody TrainingRequest trainingRequest)
    {
        
        if (!trainingRunning.tryAcquire()) {
            logger.info("Received training request but trainer is currently busy ["
                    + HttpStatus.TOO_MANY_REQUESTS + "]");
            return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
        }

        try {
            trainModel(trainingRequest.toInceptionRequest());
        }
        catch (Exception e) {
            logger.error("Error while training [" + HttpStatus.INTERNAL_SERVER_ERROR + "]", e);
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    private synchronized void trainModel(InceptionRequest inceptionReq) throws Exception
    {
        Thread t = new Thread(new Runnable()
        {

            @Override
            public synchronized void run()
            {
                try {
                    InceptionRecommenderModel trainedModel = null;
                    trainedModel = trainer.train(inceptionReq);
                    
                    // Only one model can be written to disc at a time during this time no reads are permitted
                    try {
                        while (!readModelRepPermitted.tryAcquire()) {
                            logger.debug(
                                    "Model repository is being read - check-in of new model pending");
                            wait();
                        }
                        repository.checkInModel(trainedModel, true);
                    }
                    finally {
                        readModelRepPermitted.release();
                    }
                }
                catch (Exception e) {
                    StringBuilder sb = new StringBuilder();
                    Arrays.stream(e.getStackTrace()).forEach(x->sb.append(x.toString()+"\n"));
                    logger.error(
                            "Model training error occurred [" + sb.toString() + "]");
                    e.printStackTrace();
                }
                finally {
                    trainingRunning.release();
                    logger.debug("Semaphores released - permits available: train["
                            + trainingRunning.availablePermits() + "] readRepPermitted: ["
                            + readModelRepPermitted.availablePermits() + "]");
                }
            }

        });
        t.start();
        logger.info("Model training started asynchronously");
    }
    
    @RequestMapping(value = "/predict", method = RequestMethod.POST)
    public ResponseEntity<String> executePrediction(
            @RequestBody PredictionRequest predictionRequest)
    {
        logger.info("Receivied prediction request for layer ["+predictionRequest.toInceptionRequest().getLayer()+"]");
        
        if (!readModelRepPermitted.tryAcquire()) {
            logger.debug("Model repository is being updated; try again later - http-code ["
                    + HttpStatus.PRECONDITION_FAILED + "]");
            return new ResponseEntity<>(HttpStatus.PRECONDITION_FAILED);
        }

        try {
            String modelName = predictionRequest.toInceptionRequest().getLayer();
            if (requestedModelIsNotAvailable(modelName)) {
                logger.debug("Model [" + modelName + "] is not available - http-code ["
                        + HttpStatus.PRECONDITION_FAILED + "]");
                return new ResponseEntity<>(HttpStatus.PRECONDITION_FAILED);
            }

            try {
                logger.debug("Preconditions met, start predicting");
                String response = prediction(predictionRequest.toInceptionRequest());
                return new ResponseEntity<>(response, HttpStatus.OK);
            }
            catch (Exception e) {
                logger.error("Error while training - http-code [" + HttpStatus.INTERNAL_SERVER_ERROR
                        + "]", e);
                return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        finally {
            notifyReleaseRepositoryLock();
        }
    }

    private synchronized void notifyReleaseRepositoryLock()
    {
        readModelRepPermitted.release();
        notify();        
    }

    private boolean requestedModelIsNotAvailable(String repKey)
    {
        try {
            return repository.getModel(repKey) == null;
        }
        catch (NullPointerException ne) {
            return true;
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