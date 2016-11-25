/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.opencv;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.color.ColorDeconvolution;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.plugins.AbstractTileableDetectionPlugin;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.AWTAreaROI;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.ROI;

/**
 * Simple plugin to attempt a very fast cell counting based upon (smoothed) peak detection.
 * 
 * Currently, only H&E or hematoxylin and DAB are supported.
 * 
 * An improved plugin would be desirable to perform the task in a more general way, and without 
 * requesting the 'magnification' by default (which is less meaningful than resolution/pixel size).
 * 
 * @author Pete Bankhead
 *
 */
public class CellCountsCV extends AbstractTileableDetectionPlugin<BufferedImage> {

	private static Logger logger = LoggerFactory.getLogger(CellCountsCV.class);
	
	private static String HEMATOXYLIN = "Hematoxylin";
	private static String DAB = "DAB";
	private static String HEMATOXYLIN_PLUS_DAB = "Hematoxylin + DAB";
	
	private static List<String> STAIN_CHANNELS = Arrays.asList(HEMATOXYLIN, DAB, HEMATOXYLIN_PLUS_DAB);
	
	static class FastCellCounter implements ObjectDetector<BufferedImage> {

		// TODO: REQUEST DOWNSAMPLE IN PLUGINS
		private String lastResult = null;

		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) {
			// Create a list for detected objects
			List<PathObject> pathObjects = new ArrayList<>();

			// Extract parameters
			String stainChannel = (String)params.getChoiceParameterValue("stainChannel");
			// Default to hematoxylin
			if (stainChannel == null) {
				logger.debug("Stain channel not set - will default to 'Hematoxylin'");
				stainChannel = "Hematoxylin";
			}
			double magnification = params.getDoubleParameterValue("magnification");
			boolean hasMicrons = imageData != null && imageData.getServer() != null && imageData.getServer().hasPixelSizeMicrons();
			double threshold = params.getDoubleParameterValue("threshold");
			boolean doDoG = params.getBooleanParameterValue("doDoG");
			boolean ensureMainStain = params.getBooleanParameterValue("ensureMainStain");
			
			// Radius (in pixels) of the region to show
			double radius = params.getDoubleParameterValue("detectionDiameter") / 2;
			if (!Double.isFinite(radius) || radius < 0)
				radius = 10;

			// Get the filter size & calculate a suitable downsample value
			double gaussianSigma;
			double backgroundRadius;
			double downsample = imageData.getServer().getMagnification() / magnification;
			if (downsample < 1)
				downsample = 1;
			if (hasMicrons) {
				// Determine the filter sizes in terms of pixels for the full-resolution image
				gaussianSigma = params.getDoubleParameterValue("gaussianSigmaMicrons") / imageData.getServer().getAveragedPixelSizeMicrons();
				backgroundRadius = params.getDoubleParameterValue("backgroundRadiusMicrons") / imageData.getServer().getAveragedPixelSizeMicrons();
				// If we don't have a downsample factor based on magnification, determine one from the Gaussian filter size - 
				// aiming for a sigma value of at approximately 1.25 pixels
				if (!Double.isFinite(downsample)) {
					downsample = Math.max(1, Math.round(gaussianSigma / 1.25));
//					System.err.println("Downsample: " + downsample);
				}
				// Update filter sizes according to downsampling factor
				gaussianSigma /= downsample;
				backgroundRadius /= downsample;
			}
			else {
				// Default to a downsample of 1 if we don't know better
				if (!Double.isFinite(downsample))
					downsample = 1;
				// Get filter sizes in terms of pixels for the image to process
				gaussianSigma = params.getDoubleParameterValue("gaussianSigmaPixels") / downsample;
				backgroundRadius = params.getDoubleParameterValue("backgroundRadiusPixels") / downsample;
			}
			logger.debug("Fast cell counting with Gaussian sigma {} pixels, downsample {}", gaussianSigma, downsample);
//			System.err.println("ACTUAL Downsample: " + downsample);

			
			// Read the buffered image
			ImageServer<BufferedImage> server = imageData.getServer();
			RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, pathROI);
			BufferedImage img = server.readBufferedImage(request);
			
			// Get the top left corner for later adjustments
			double x = request.getX();
			double y = request.getY();
			double scaleX = request.getWidth() / (double)img.getWidth();
			double scaleY = request.getHeight() / (double)img.getHeight();

			/*
			 * Color deconvolution
			 */
			
			// Get channels
			ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
			int[] rgb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
			float[] pxNucleusStain = ColorDeconvolution.colorDeconvolveRGBArray(rgb, stains, 0, null);
			float[] pxStain2 = ColorDeconvolution.colorDeconvolveRGBArray(rgb, stains, 1, null);

			// Positive channel threshold
			double stain2Threshold = (imageData.isBrightfield() && imageData.getColorDeconvolutionStains().isH_DAB()) ? params.getDoubleParameterValue("thresholdDAB") : -1;

			// Update the detection channel, if required
			if (stainChannel.equals(DAB)) {
				for (int i = 0; i < pxNucleusStain.length; i++) {
					pxNucleusStain[i] = pxStain2[i];
				}
			} else if (stainChannel.equals(HEMATOXYLIN_PLUS_DAB)) {
				for (int i = 0; i < pxNucleusStain.length; i++) {
					pxNucleusStain[i] += pxStain2[i];
				}				
			}
			
//			float[][] pxDeconvolved = WatershedNucleiCV.colorDeconvolve(img, stains.getStain(1).getArray(), stains.getStain(2).getArray(), null, 2);
//			float[] pxHematoxylin = pxDeconvolved[0];
			
			// Convert to OpenCV Mat
			int width = img.getWidth();
			int height = img.getHeight();
			Mat matOrig = new Mat(height, width, CvType.CV_32FC1);
			
			// It seems OpenCV doesn't use the array directly, so no need to copy...
			matOrig.put(0, 0, pxNucleusStain);
			
			/*
			 * Detection
			 */
			
			// Subtract opened image
			if (backgroundRadius > 0) {
				Mat matBG = new Mat();
				int size = (int)Math.round(backgroundRadius) * 2 + 1;
				Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(size, size));
				Imgproc.morphologyEx(matOrig, matBG, Imgproc.MORPH_OPEN, kernel);
				Core.subtract(matOrig, matBG, matOrig);
			}
			
			
			
			// Apply Gaussian filter
			int gaussianWidth = (int)(Math.ceil(gaussianSigma * 3) * 2 + 1);
			Mat mat = new Mat(); // From now on, work with the smoothed image
			Imgproc.GaussianBlur(matOrig, mat, new Size(gaussianWidth, gaussianWidth), gaussianSigma);
			
			// Filter the second stain as well
			Mat matStain2 = new Mat(height, width, CvType.CV_32FC1);
			matStain2.put(0, 0, pxStain2);
			Imgproc.GaussianBlur(matStain2, matStain2, new Size(gaussianWidth, gaussianWidth), gaussianSigma);
			
			// Apply basic threshold to identify potential nucleus pixels
			Mat matThresh = new Mat();
			Core.compare(mat, new Scalar(threshold), matThresh, Core.CMP_GE);
			
			// Ensure cells selected only where hematoxylin > eosin/DAB, if required
			if (ensureMainStain) {
				Mat matValid = new Mat();
				Core.compare(mat, matStain2, matValid, Core.CMP_GE);
				Core.min(matThresh, matValid, matThresh);
				matValid.release();
			}
			
			// Do Difference of Gaussians, if required
			if (doDoG) {
				double sigma2 = gaussianSigma * 1.6;
				int gaussianWidth2 = (int)(Math.ceil(sigma2 * 3) * 2 + 1);
				Mat mat2 = new Mat();
				// Apply filter to the original
				Imgproc.GaussianBlur(matOrig, mat2, new Size(gaussianWidth2, gaussianWidth2), sigma2);
				Core.subtract(mat, mat2, mat);
			}
			
			// Apply max filter to help find maxima
			Mat matMax = new Mat(mat.size(), mat.type());
			Imgproc.dilate(mat, matMax, new Mat());

			// Apply potential maxima threshold by locating pixels where mat == matMax,
			// i.e. a pixel is equal to the maximum of its 8 neighbours
			// (Note: this doesn’t deal with points of inflection, but with 32-bit this is likely to be rare enough
			// not to be worth the considerably extra computational cost; may need to confirm there are no rounding errors)
			Mat matMaxima = new Mat();
			Core.compare(mat, matMax, matMaxima, Core.CMP_EQ);
			
			// Compute AND of two binary images
			// This finds the potential nucleus pixels that are also local maxima in the processed image
			Core.min(matThresh, matMaxima, matMaxima);
			
			/*
			 * Create objects
			 */
			
			// Create path objects from contours
			// This deals with the fact that maxima located within matMaxima (a binary image) aren’t necessarily
			// single pixels, but should be treated as belonging to the same cell		
			List<MatOfPoint> contours = new ArrayList<>();
			Mat temp = new Mat();
			Imgproc.findContours(matMaxima, contours, temp, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
			temp.release();
			ArrayList<qupath.lib.geom.Point2> points = new ArrayList<>();

			Shape shape = pathROI instanceof PathArea ? PathROIToolsAwt.getShape(pathROI) : null;
			Integer color = ColorTools.makeRGB(0, 255, 0);
			float[] stain2Value = new float[1];
			String stain2Name = stains.getStain(2).getName();
			PathArea area = pathROI instanceof PathArea ? (PathArea)pathROI : null;
			if (area instanceof AreaROI && !(area instanceof AWTAreaROI))
				area = new AWTAreaROI((AreaROI)area);
			
			boolean detectInPositiveChannel = stainChannel.equals(DAB);
			for (MatOfPoint contour : contours){

				// This doesn't appear to work...
//				Moments moments = Imgproc.moments(contour, false);
//				int cx = (int)(moments.m10/moments.m00);
//				int cy = (int)(moments.m01/moments.m00);
				
				// Create a polygon ROI
				points.clear();
				for (Point p : contour.toArray())
					points.add(new qupath.lib.geom.Point2((p.x + 0.5) * scaleX + x, (p.y + 0.5) * scaleY + y));

				// Add new polygon if it is contained within the ROI
				ROI tempROI = null;
				if (points.size() == 1) {
					qupath.lib.geom.Point2 p = points.get(0);
					if (shape != null && !shape.contains(p.getX(), p.getY())) {
						continue;
					}
					
					// Check we're inside
					if (area != null && !area.contains(p.getX(), p.getY()))
						continue;
					
					tempROI = new EllipseROI(p.getX()-radius, p.getY()-radius, radius*2, radius*2);
				}
				else {
					tempROI = new PolygonROI(points);
					// Check we're inside
					if (area != null && !area.contains(tempROI.getCentroidX(), tempROI.getCentroidY()))
						continue;
					tempROI = new EllipseROI(tempROI.getCentroidX()-radius, tempROI.getCentroidY()-radius, radius*2, radius*2);
				}

				PathObject pathObject = new PathDetectionObject(tempROI);
				// Check stain2 value at the peak pixel, if required
				if (stain2Threshold >= 0) {
					int cx = (int)((tempROI.getCentroidX() - x)/scaleX);
					int cy = (int)((tempROI.getCentroidY() - y)/scaleY);
					matStain2.get(cy, cx, stain2Value);
					if (detectInPositiveChannel || stain2Value[0] >= stain2Threshold)
						pathObject.setPathClass(PathClassFactory.getPositive(null, null));
					else
						pathObject.setPathClass(PathClassFactory.getNegative(null, null));
					pathObject.getMeasurementList().putMeasurement(stain2Name + " OD", stain2Value[0]);
					pathObject.getMeasurementList().closeList();
				} else
					pathObject.setColorRGB(color);

				contour.release();
				pathObjects.add(pathObject);
			}
			logger.info("Found " + pathObjects.size() + " contours");

			
			// Release matrices
			matThresh.release();
			matMax.release();
			matMaxima.release();
			matOrig.release();
			matStain2.release();
			
			lastResult = "Detected " + pathObjects.size() + " cells";
			
			return pathObjects;
		}

		@Override
		public String getLastResultsDescription() {
			return lastResult;
		}

	}


	@Override
	protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {
		ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
		if (!imageData.isBrightfield() || stains == null || !(stains.isH_E() || stains.isH_DAB())) {
			throw new IllegalArgumentException("This command only supports brightfield images with H&E or H-DAB staining, sorry!");
//			return false;
		}
		return super.parseArgument(imageData, arg);
	}
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		ParameterList params = new ParameterList()
				.addTitleParameter("Detection image")
				.addChoiceParameter("stainChannel", "Cell detection channel", HEMATOXYLIN, STAIN_CHANNELS, "Choose channel that will be thresholded to detect the cells")
				// Magnification is deprecated!  Will be hidden, and only kept here for some backwards compatibility
				.addDoubleParameter("magnification", "Requested magnification", Double.NaN, null, "Magnification at which the detection should be run")
				.addDoubleParameter("gaussianSigmaPixels", "Gaussian sigma", 1, "px", "Smoothing filter uses to reduce spurious peaks")
				.addDoubleParameter("gaussianSigmaMicrons", "Gaussian sigma", 1.5, GeneralTools.micrometerSymbol(), "Smoothing filter uses to reduce spurious peaks")
				.addDoubleParameter("backgroundRadiusPixels", "Background radius", 20, "px", "Filter size to estimate background; should be > the largest nucleus radius")
				.addDoubleParameter("backgroundRadiusMicrons", "Background radius", 15, GeneralTools.micrometerSymbol(), "Filter size to estimate background; should be > the largest nucleus radius")
				.addBooleanParameter("doDoG", "Use Difference of Gaussians", true, "Apply Difference of Gaussians filter prior to detection - this tends to detect more nuclei, but may detect too many")
				.addTitleParameter("Thresholding")
				.addDoubleParameter("threshold", "Cell detection threshold", 0.1, null, "Hematoxylin intensity threshold")
				.addDoubleParameter("thresholdDAB", "DAB threshold", 0.2, null, "DAB OD threshold for positive percentage counts")
				.addBooleanParameter("ensureMainStain", "Hematoxylin predominant", false, "Accept detection only if haematoxylin value is higher than that of the second deconvolved stain")
				.addTitleParameter("Display")
				.addDoubleParameter("detectionDiameter", "Detection object diameter", 25, "pixels", "Adjust the size of detection object that is created around each peak (note, this does not influence which cells are detected");

		// Magnification is deprecated!
		params.setHiddenParameters(true, "magnification");

		boolean isHDAB = imageData.isBrightfield() && imageData.getColorDeconvolutionStains().isH_DAB();
		params.setHiddenParameters(!isHDAB, "stainChannel");		
		params.setHiddenParameters(isHDAB, "ensureMainStain");
		params.setHiddenParameters(!isHDAB, "thresholdDAB");
		
		boolean hasMicrons = imageData != null && imageData.getServer() != null && imageData.getServer().hasPixelSizeMicrons();
		params.getParameters().get("gaussianSigmaPixels").setHidden(hasMicrons);
		params.getParameters().get("gaussianSigmaMicrons").setHidden(!hasMicrons);
		params.getParameters().get("backgroundRadiusPixels").setHidden(hasMicrons);
		params.getParameters().get("backgroundRadiusMicrons").setHidden(!hasMicrons);
		return params;
	}

	@Override
	public String getName() {
		return "Fast cell counts";
	}

	@Override
	public String getDescription() {
		return "Perform a fast, low-resolution count of nuclei in a whole slide image stained with H&E or hematoxylin and DAB using a peak-finding approach";
	}

	@Override
	public String getLastResultsDescription() {
		return null; // cellCounter == null ? "" : cellCounter.getLastResultsDescription();
	}

//	@Override
//	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
////		if (detector == null)
//			detector = new FastNucleusDetector();
//		tasks.add(createRunnableTask(detector, getCurrentParameterList(imageData), imageData, parentObject));
//	}

	@Override
	protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
		return 2;
	}

	@Override
	protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
		return new FastCellCounter();
	}

	/**
	 * Returns zero - indicating no overlap... the aim is speed.
	 */
	@Override
	protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
		return 0;
	}


}