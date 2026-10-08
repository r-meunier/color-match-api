package com.rmeunier.colormatchapi.service.impl;

import com.google.cloud.vision.v1.*;
import com.google.type.Color;
import com.rmeunier.colormatchapi.exception.ColorMissingException;
import com.rmeunier.colormatchapi.exception.ResourceNotFoundException;
import com.rmeunier.colormatchapi.model.Schema;
import com.rmeunier.colormatchapi.service.IVisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.cloud.spring.vision.CloudVisionTemplate;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

@Service
public class VisionService implements IVisionService {

    @Autowired
    private CloudVisionTemplate cloudVisionTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    private static final Logger LOGGER = LoggerFactory.getLogger(VisionService.class);

    /**
     * Finds and loads the image file as a resource and runs the Google Vision API on it to retreive the image properties.
     * From the image properties, it gets the dominant color back.
     * The dominant color is the first color returned by the API.
     *
     * @param filePath the file path to the image
     * @param schema the way the HTTP connection should be handled
     * @return the RGB vector for the dominant color
     * @throws ResourceNotFoundException if the image resource cannot be loaded.
     */
    public int[] loadDominantColorForImage(String filePath, Schema schema) throws ResourceNotFoundException {
        Resource imgResource = getImageResourceFromUrl(filePath, schema);

        if (imgResource == null) {
            throw new ResourceNotFoundException(filePath);
        }

        if (!imgResource.exists()) {
            LOGGER.error("An error occurred during loading of image resource!");
            throw new ResourceNotFoundException(filePath);
        }

        AnnotateImageResponse response = this.cloudVisionTemplate
                .analyzeImage(imgResource, Feature.Type.IMAGE_PROPERTIES);

        ImageProperties imageProperties = response.getImagePropertiesAnnotation();
        Color dominantColor = getDominantColors(imageProperties);

        if (dominantColor == null) {
            LOGGER.error("Could not load dominant color!");
            throw new ColorMissingException();
        }

        int red = (int) dominantColor.getRed();
        int green = (int) dominantColor.getGreen();
        int blue = (int) dominantColor.getBlue();

        int[] rgb = {red, green, blue};

        LOGGER.debug("Dominant color: {}", Arrays.toString(rgb));

        return rgb;
    }

    /**
     * Retrieves the dominant color from the colors list returned by the Vision API.
     * The dominant color is the first color of the list.
     * @param imageProperties the properties of the image already loaded by the Vision API.
     * @return the Color object containing the primary dominant color.
     */
    private Color getDominantColors(ImageProperties imageProperties) {
        DominantColorsAnnotation colors = imageProperties.getDominantColors();
        List<ColorInfo> colorInfos = colors.getColorsList();

        if (colorInfos.size() <= 0) {
            LOGGER.error("Could not obtain any colors from this image!");
            return null;
        }

        return colorInfos.get(0).getColor();
    }

    /**
     * Loads the resource using an input stream and a HTTPS connection.
     * @param filePath the path to the image file.
     * @param schema the HTTP request schema, in this case, HTTPS.
     * @return the image resource loaded from InputStream.
     */
    private Resource getImageResourceFromUrl(String filePath, Schema schema) {
        String fullFilePath = schema.label + filePath;
        InputStreamResource imgResource = null;

        try {
            URL url = URI.create(fullFilePath).toURL();
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            InputStream in = conn.getInputStream();

            imgResource = new InputStreamResource(in);

        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error("Error occurred loading the image resource... Error: {}", e.getMessage());
        }

        return imgResource;
    }
}
