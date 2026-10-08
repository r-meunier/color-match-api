package com.rmeunier.colormatchapi.config;

import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

public class ImportParamValidator implements JobParametersValidator {
    @Override
    public void validate(JobParameters jobParameters) throws InvalidJobParametersException {
        if (jobParameters == null) {
            throw new InvalidJobParametersException("Job parameters could not be retrieved.");
        }

        if (jobParameters.getString("filePath") == null || jobParameters.getString("filePath").isEmpty()) {
            throw new InvalidJobParametersException("File path could not be found.");
        }
    }
}
