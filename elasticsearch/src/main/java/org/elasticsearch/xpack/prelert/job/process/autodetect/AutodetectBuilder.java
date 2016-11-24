/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.prelert.job.AnalysisLimits;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelDebugConfig;
import org.elasticsearch.xpack.prelert.job.process.NativeController;
import org.elasticsearch.xpack.prelert.job.process.ProcessCtrl;
import org.elasticsearch.xpack.prelert.job.process.ProcessPipes;
import org.elasticsearch.xpack.prelert.job.process.autodetect.writer.AnalysisLimitsWriter;
import org.elasticsearch.xpack.prelert.job.process.autodetect.writer.FieldConfigWriter;
import org.elasticsearch.xpack.prelert.job.process.autodetect.writer.ModelDebugConfigWriter;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.lists.ListDocument;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * The autodetect process builder.
 */
public class AutodetectBuilder {
    private static final String CONF_EXTENSION = ".conf";
    private static final String LIMIT_CONFIG_ARG = "--limitconfig=";
    private static final String MODEL_DEBUG_CONFIG_ARG = "--modeldebugconfig=";
    private static final String FIELD_CONFIG_ARG = "--fieldconfig=";

    private Job job;
    private List<Path> filesToDelete;
    private Logger logger;
    private boolean ignoreDowntime;
    private Set<ListDocument> referencedLists;
    private Optional<Quantiles> quantiles;
    private Environment env;
    private Settings settings;
    private NativeController controller;
    private ProcessPipes processPipes;

    /**
     * Constructs an autodetect process builder
     *
     * @param job           The job configuration
     * @param filesToDelete This method will append File objects that need to be
     *                      deleted when the process completes
     * @param logger        The job's logger
     */
    public AutodetectBuilder(Job job, List<Path> filesToDelete, Logger logger, Environment env, Settings settings,
                             NativeController controller, ProcessPipes processPipes) {
        this.env = env;
        this.settings = settings;
        this.controller = controller;
        this.processPipes = processPipes;
        this.job = Objects.requireNonNull(job);
        this.filesToDelete = Objects.requireNonNull(filesToDelete);
        this.logger = Objects.requireNonNull(logger);
        ignoreDowntime = false;
        referencedLists = new HashSet<>();
        quantiles = Optional.empty();
    }

    /**
     * Set ignoreDowntime
     *
     * @param ignoreDowntime If true set the ignore downtime flag overriding the
     *                       setting in the job configuration
     */
    public AutodetectBuilder ignoreDowntime(boolean ignoreDowntime) {
        this.ignoreDowntime = ignoreDowntime;
        return this;
    }

    public AutodetectBuilder referencedLists(Set<ListDocument> lists) {
        referencedLists = lists;
        return this;
    }

    /**
     * Set quantiles to restore the normaliser state if any.
     *
     * @param quantiles the non-null quantiles
     */
    public AutodetectBuilder quantiles(Optional<Quantiles> quantiles) {
        this.quantiles = quantiles;
        return this;
    }

    /**
     * Requests that the controller daemon start an autodetect process.
     */
    public void build() throws IOException, TimeoutException {

        List<String> command = ProcessCtrl.buildAutodetectCommand(env, settings, job, logger, ignoreDowntime, controller.getPid());

        buildLimits(command);
        buildModelDebugConfig(command);

        buildQuantiles(command);
        buildFieldConfig(command);
        processPipes.addArgs(command);
        controller.startProcess(command);
    }

    private void buildLimits(List<String> command) throws IOException {
        if (job.getAnalysisLimits() != null) {
            Path limitConfigFile = Files.createTempFile(env.tmpFile(), "limitconfig", CONF_EXTENSION);
            filesToDelete.add(limitConfigFile);
            writeLimits(job.getAnalysisLimits(), limitConfigFile);
            String limits = LIMIT_CONFIG_ARG + limitConfigFile.toString();
            command.add(limits);
        }
    }

    /**
     * Write the Prelert autodetect model options to <code>emptyConfFile</code>.
     */
    private static void writeLimits(AnalysisLimits options, Path emptyConfFile) throws IOException {

        try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(emptyConfFile), StandardCharsets.UTF_8)) {
            new AnalysisLimitsWriter(options, osw).write();
        }
    }

    private void buildModelDebugConfig(List<String> command) throws IOException {
        if (job.getModelDebugConfig() != null) {
            Path modelDebugConfigFile = Files.createTempFile(env.tmpFile(), "modeldebugconfig", CONF_EXTENSION);
            filesToDelete.add(modelDebugConfigFile);
            writeModelDebugConfig(job.getModelDebugConfig(), modelDebugConfigFile);
            String modelDebugConfig = MODEL_DEBUG_CONFIG_ARG + modelDebugConfigFile.toString();
            command.add(modelDebugConfig);
        }
    }

    private static void writeModelDebugConfig(ModelDebugConfig config, Path emptyConfFile)
            throws IOException {
        try (OutputStreamWriter osw = new OutputStreamWriter(
                Files.newOutputStream(emptyConfFile),
                StandardCharsets.UTF_8)) {
            new ModelDebugConfigWriter(config, osw).write();
        }
    }

    private void buildQuantiles(List<String> command) throws IOException {
        if (quantiles.isPresent() && !quantiles.get().getQuantileState().isEmpty()) {
            Quantiles quantiles = this.quantiles.get();
            logger.info("Restoring quantiles for job '" + job.getId() + "'");

            Path normalisersStateFilePath = ProcessCtrl.writeNormaliserInitState(
                    job.getId(), quantiles.getQuantileState(), env);

            String quantilesStateFileArg = ProcessCtrl.QUANTILES_STATE_PATH_ARG + normalisersStateFilePath;
            command.add(quantilesStateFileArg);
            command.add(ProcessCtrl.DELETE_STATE_FILES_ARG);
        }
    }

    private void buildFieldConfig(List<String> command) throws IOException, FileNotFoundException {
        if (job.getAnalysisConfig() != null) {
            // write to a temporary field config file
            Path fieldConfigFile = Files.createTempFile(env.tmpFile(), "fieldconfig", CONF_EXTENSION);
            filesToDelete.add(fieldConfigFile);
            try (OutputStreamWriter osw = new OutputStreamWriter(
                    Files.newOutputStream(fieldConfigFile),
                    StandardCharsets.UTF_8)) {
                new FieldConfigWriter(job.getAnalysisConfig(), referencedLists, osw, logger).write();
            }

            String fieldConfig = FIELD_CONFIG_ARG + fieldConfigFile.toString();
            command.add(fieldConfig);
        }
    }
}
