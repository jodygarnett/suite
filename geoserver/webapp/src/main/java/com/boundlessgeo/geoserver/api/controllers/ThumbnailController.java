/* (c) 2015 Boundless, http://boundlessgeo.com
 * This code is licensed under the GPL 2.0 license.
 */
package com.boundlessgeo.geoserver.api.controllers;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerGroupHelper;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.PublishedInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.config.GeoServer;
import org.geoserver.wms.GetMapRequest;
import org.geoserver.wms.MapLayerInfo;
import org.geoserver.wms.WebMap;
import org.geoserver.wms.WebMapService;
import org.geoserver.wms.map.RenderedImageMap;
import org.geotools.styling.Style;
import org.geotools.util.logging.Logging;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.boundlessgeo.geoserver.AppConfiguration;
import com.boundlessgeo.geoserver.catalog.ThumbnailInvalidatingCatalogListener;
import com.google.common.util.concurrent.Striped;
import com.vividsolutions.jts.geom.Envelope;

@Controller
@RequestMapping("/api/thumbnails")
public class ThumbnailController extends ApiController {
    
    private static Logger LOG = Logging.getLogger(ThumbnailController.class);
    static final String TYPE = "png";
    static final String MIME_TYPE = "image/png";
    public static final String EXTENSION = ".png";
    public static final String EXTENSION_HR = "@2x.png";
    public static final int THUMBNAIL_SIZE = 75;
    
    @Autowired
    @Qualifier("wmsServiceTarget")
    WebMapService wms;
    
    @Autowired
    AppConfiguration config;
    
    Striped<Semaphore> semaphores = Striped.semaphore(4, 1);
    
    @Autowired
    public ThumbnailController(GeoServer geoServer) {
        super(geoServer);
    }
    
    /**
     * Endpoint to retrieve a cached thumbnail for a map, generating it if it does not exist.
     * If the request parameter "hiRes=true" is provided, returns a high-resolution (x2) thumbnail.
     * @param wsName Workspace name
     * @param name Map name
     * @param hiRes Whether or not to return a high-res thumbnail. Optional, defaults to false
     * @param request
     * @return HttpEntity containing the thumbnail image as a byte array
     * @throws Exception
     */
    @RequestMapping(value = "/maps/{wsName:.+}/{name:.+}", method = RequestMethod.GET)
    public HttpEntity<byte[]> getMap(@PathVariable String wsName, 
            @PathVariable String name, 
            @RequestParam(value="hiRes", required=false, defaultValue="false") Boolean hiRes,
            HttpServletRequest request) throws Exception {
        
        Catalog catalog = geoServer.getCatalog();
        WorkspaceInfo ws = findWorkspace(wsName, catalog);
        LayerGroupInfo map = findMap(wsName, name, catalog);
        
        return get(ws, map, hiRes);
    }
    
    /**
     * Endpoint to retrieve a cached thumbnail for a layer, generating it if it does not exist.
     * If the request parameter "hiRes=true" is provided, returns a high-resolution (x2) thumbnail.
     * @param wsName Workspace name
     * @param name Layer name
     * @param hiRes Whether or not to return a high-res thumbnail. Optional, defaults to false
     * @param request
     * @return HttpEntity containing the thumbnail image as a byte array
     * @throws Exception
     */
    @RequestMapping(value = "/layers/{wsName:.+}/{name:.+}", method = RequestMethod.GET)
    public HttpEntity<byte[]> getLayer(@PathVariable String wsName, 
            @PathVariable String name, 
            @RequestParam(value="hiRes", required=false, defaultValue="false") Boolean hiRes,
            HttpServletRequest request) throws Exception {
        
        Catalog catalog = geoServer.getCatalog();
        WorkspaceInfo ws = findWorkspace(wsName, catalog);
        LayerInfo layer = findLayer(wsName, name, catalog);
        
        return get(ws, layer, hiRes);
    }
    
    /**
     * Retrieve or create the thumbnail for a PublishedInfo
     * @param ws Workspace for the layer
     * @param layer LayerInfo or LayerGroupInfo to get the thumbnail of
     * @param hiRes Flag to return hi-res (x2) thumbnail
     * @return HttpEntity containing the thumbnail image as a byte array
     * @throws Exception
     */
    public HttpEntity<byte[]> get(WorkspaceInfo ws, PublishedInfo layer, boolean hiRes) throws Exception {
        String path = Metadata.thumbnail(layer);
        FileInputStream in = null;
        
        //Has not been generated yet, use WMS reflector
        if (path == null) {
            createThumbnail(ws, layer);
            path = Metadata.thumbnail(layer);
        }
        File thumbnailFile;
        //If the file has been deleted, recreate it
        try {
            thumbnailFile = config.getCacheFile(path);
        } catch (FileNotFoundException e) {
            createThumbnail(ws, layer);
            path = Metadata.thumbnail(layer);
        }
        try {
            if (hiRes) {
                thumbnailFile = config.getCacheFile(path.replaceAll(
                        EXTENSION+"$", EXTENSION_HR));
            } else {
                thumbnailFile = config.getCacheFile(path);
            }
            in = new FileInputStream(thumbnailFile);
            byte[] bytes = IOUtils.toByteArray(in);
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(MIME_TYPE));
            headers.setLastModified(thumbnailFile.lastModified());
            return new HttpEntity<byte[]>(bytes, headers);
        } finally {
            if (in != null) { in.close(); }
        }
    }
    
    /**
     * Creates a thumbnail for the layer as a Resource, and updates the layer with the new thumbnail
     * @param ws The workspace of the layer
     * @param layer The layer or layerGroup to get the thumbnail for
     * @return The thumbnail image as a Resource
     * @throws Exception
     */
    protected void createThumbnail(WorkspaceInfo ws, PublishedInfo layer) throws Exception {
        Catalog catalog = geoServer.getCatalog();
        //Sync against this map/layer
        Semaphore s = semaphores.get(layer);
        s.acquire();
        try {
            //Set up getMap request
            GetMapRequest request = new GetMapRequest();
            
            List<MapLayerInfo> layers = new ArrayList<MapLayerInfo>();
            List<Style> styles = new ArrayList<Style>();
            Envelope bbox = null;
            if (layer instanceof LayerInfo) {
                layers.add(new MapLayerInfo((LayerInfo)layer));
                styles.add(((LayerInfo)layer).getDefaultStyle().getStyle());
                bbox = ((LayerInfo)layer).getResource().boundingBox();
            } else if (layer instanceof LayerGroupInfo) {
                LayerGroupHelper helper = new LayerGroupHelper((LayerGroupInfo)layer);
                bbox = ((LayerGroupInfo)layer).getBounds();
                
                List<LayerInfo> layerList = helper.allLayersForRendering();
                for (int i = 0; i  < layerList.size(); i++) {
                    layers.add(new MapLayerInfo(layerList.get(i)));
                }
                List<StyleInfo> styleList = helper.allStylesForRendering();
                for (int i = 0; i  < styleList.size(); i++) {
                    if (styleList.get(i) == null) {
                        styles.add(layerList.get(i).getDefaultStyle().getStyle());
                    } else {
                        styles.add(styleList.get(i).getStyle());
                    }
                }
            } else {
                throw new RuntimeException("layer must be one of LayerInfo or LayerGroupInfo");
            }
            request.setLayers(layers);
            request.setStyles(styles);
            request.setFormat(MIME_TYPE);
            
            //Set the size of the HR thumbnail
            //Take the smallest bbox dimension as the min dimension. We can then crop the other 
            //dimension to give a square thumbnail
            request.setBbox(bbox);
            if (bbox.getWidth() < bbox.getHeight()) {
                request.setWidth(2*THUMBNAIL_SIZE);
            } else {
                request.setHeight(2*THUMBNAIL_SIZE);
            }
            
            //Run the getMap request through the WMS Reflector
            WebMap response = wms.reflect(request);
            //Get the resulting map image
            BufferedImage image;
            if (response instanceof RenderedImageMap) {
                RenderedImageMap map = (RenderedImageMap)response;
                assert(map.getImage() instanceof BufferedImage);
                image = (BufferedImage)map.getImage();
            } else {
                throw new RuntimeException("Unsupported getMap response format:" + response.getClass().getName());
            }
            
            writeThumbnail(layer, image);
            Metadata.thumbnail(layer, thumbnailFilename(layer));
            
            if (layer instanceof LayerInfo) {
                catalog.save((LayerInfo)layer);
            } else if (layer instanceof LayerGroupInfo) {
                catalog.save((LayerGroupInfo)layer);
            }
        } finally {
            s.release();
        }
    }
    
    /**
     * Clears any cached thumbnail information (called by {@link ThumbnailInvalidatingCatalogListener} when a layer is removed).
     * 
     * @param layer
     * @throws InterruptedException 
     */
    public void clearThumbnail(PublishedInfo layer) {
        Semaphore s = semaphores.get(layer);
        try {
            s.acquire();
        } catch (InterruptedException e) {
            LOG.finer("Unable to clear thumbnail for "+layer.prefixedName()+":"+e.getMessage());
            return;
        }
        try {
            File loRes = config.cacheFile(thumbnailFilename(layer));
            if( loRes.exists() ){
                boolean removed = loRes.delete();
                if (!removed) {
                    loRes.deleteOnExit();
                }
            }
            File hiRes = config.cacheFile(thumbnailFilename(layer,true));
            if( hiRes.exists() ){
                boolean removed = hiRes.delete();
                if (!removed) {
                    hiRes.deleteOnExit();
                }
            }
        } finally {
            s.release();
        }
    }
    
    protected void writeThumbnail(PublishedInfo layer, BufferedImage image) throws FileNotFoundException, IOException, InterruptedException {
      //Write the thumbnail files
        FileOutputStream loRes = null;
        FileOutputStream hiRes = null;
        
        try {
            loRes = new FileOutputStream(config.createCacheFile(thumbnailFilename(layer)));
            hiRes = new FileOutputStream(config.createCacheFile(thumbnailFilename(layer, true)));
            
            ImageIO.write(scaleImage(image, 0.5, true), TYPE, loRes);
            //Don't scale, but crop to square
            ImageIO.write(scaleImage(image, 1.0, true), TYPE, hiRes);
        } finally {
            if (loRes != null) { 
                try {
                    loRes.close(); 
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Error closing file", e);
                }
            }
            if (hiRes != null) { 
                try {
                    hiRes.close();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Error closing file", e);
                }
            }
        }
    }
    /**
     * Utility method to generate a consistent thumbnail filename
     * @param layer to create the filename for
     * @return relative filename
     */
    public static final String thumbnailFilename(PublishedInfo layer) {
        return thumbnailFilename(layer, false);
    }
    
    /**
     * Utility method to generate a consistent thumbnail filename
     * @param layer to create the filename for
     * @param hiRes is this the name of a hi-res thumbnail file?
     * @return relative filename
     */
    public static final String thumbnailFilename(PublishedInfo layer, boolean hiRes) {
        if (hiRes) {
            return layer.getId()+EXTENSION_HR;
        }
        return layer.getId()+EXTENSION;
    }
    
    public static RenderedImage scaleImage(BufferedImage image, double scale) throws IOException {
        return scaleImage(image, scale, false);
    }
    /**
     * Utility method for scaling thumbnails. Scales byte[] image by a scale factor.
     * Optionally crops images to square.
     * @param src RenderedImage containing the input image
     * @param scale Scale amount
     * @param square Boolean flag to crop to a square image
     * @return RenderedImage containing the transformed image
     * @throws IOException
     */
    public static BufferedImage scaleImage(BufferedImage image, double scale, boolean square) throws IOException {
        BufferedImage scaled = image;
        if (scale != 1.0) {
            AffineTransform scaleTransform = AffineTransform.getScaleInstance(scale, scale);
            AffineTransformOp bilinearScaleOp = new AffineTransformOp(scaleTransform, AffineTransformOp.TYPE_BILINEAR);
            scaled =  bilinearScaleOp.filter(image, new BufferedImage(
                    (int)(image.getWidth()*scale), (int)(image.getHeight()*scale), image.getType()));
        }
        if (square) {
            if (scaled.getHeight() > scaled.getWidth()) {
                scaled = scaled.getSubimage(0, (scaled.getHeight() - scaled.getWidth())/2, 
                                            scaled.getWidth(), scaled.getWidth());
            } else if (scaled.getHeight() < scaled.getWidth()) {
                scaled = scaled.getSubimage((scaled.getWidth() - scaled.getHeight())/2, 0,
                                            scaled.getHeight(), scaled.getHeight());
            }
        }
        
        return scaled;
    }
}
