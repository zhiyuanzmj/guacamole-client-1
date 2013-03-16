package org.glyptodon.guacamole.net.basic;

/*
 *  Guacamole - Clientless Remote Desktop
 *  Copyright (C) 2010  Michael Jumper
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.properties.BooleanGuacamoleProperty;
import org.glyptodon.guacamole.properties.GuacamoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple ServletContextListener which loads a WebSocket tunnel implementation
 * if available, using the Servlet 3.0 API to dynamically load and install
 * the tunnel servlet.
 *
 * Note that because Guacamole depends on the Servlet 2.5 API, and 3.0 may
 * not be available or needed if WebSocket is not desired, the 3.0 API is
 * detected and invoked dynamically via reflection.
 * 
 * Tests have shown that while WebSocket is negligibly more responsive than
 * Guacamole's native HTTP tunnel, downstream performance is not yet a match.
 * This may be because browser WebSocket implementations are not optimized for
 * throughput, or it may be because servlet container WebSocket implementations
 * are in their infancy, or it may be that OUR WebSocket-backed tunnel
 * implementations are not efficient. Because of this, WebSocket support is
 * disabled by default. To enable it, add the following property to
 * your guacamole.properties:
 * 
 *     enable-websocket: true
 *
 * @author Michael Jumper
 */
public class WebSocketSupportLoader implements ServletContextListener {

    /**
     * Logger for this class.
     */
    private Logger logger = LoggerFactory.getLogger(WebSocketSupportLoader.class);

    private static final BooleanGuacamoleProperty ENABLE_WEBSOCKET =
            new BooleanGuacamoleProperty() {

        @Override
        public String getName() {
            return "enable-websocket";
        }

    };
    
    /**
     * Classname of the Jetty-specific WebSocket tunnel implementation.
     */
    private static final String JETTY_WEBSOCKET =
        "net.sourceforge.guacamole.net.basic.websocket.jetty.BasicGuacamoleWebSocketTunnelServlet";

    /**
     * Classname of the Tomcat-specific WebSocket tunnel implementation.
     */
    private static final String TOMCAT_WEBSOCKET =
        "net.sourceforge.guacamole.net.basic.websocket.tomcat.BasicGuacamoleWebSocketTunnelServlet";

    private boolean loadWebSocketTunnel(ServletContext context, String classname) {

        try {

            // Attempt to find WebSocket servlet
            Class<Servlet> servlet = (Class<Servlet>)
                    GuacamoleClassLoader.getInstance().findClass(classname);

            // Dynamically add servlet IF SERVLET 3.0 API AVAILABLE!
            try {

                // Get servlet registration class
                Class regClass = Class.forName("javax.servlet.ServletRegistration");

                // Get and invoke addServlet()
                Method addServlet = ServletContext.class.getMethod("addServlet",
                        String.class, Class.class);
                Object reg = addServlet.invoke(context, "WebSocketTunnel", servlet);

                // Get and invoke addMapping()
                Method addMapping = regClass.getMethod("addMapping", String[].class);
                addMapping.invoke(reg, (Object) new String[]{"/websocket-tunnel"});

                // If we succesfully load and register the WebSocket tunnel servlet,
                // WebSocket is supported.
                logger.info("WebSocket support found and loaded.");
                return true;

            }

            // Servlet API 3.0 unsupported
            catch (ClassNotFoundException e) {
                logger.info("Servlet API 3.0 not found.", e);
            }
            catch (NoSuchMethodException e) {
                logger.warn("Servlet API 3.0 found, but incomplete.", e);
            }

            // Servlet API 3.0 found, but errors during use
            catch (IllegalAccessException e) {
                logger.error("Unable to load WebSocket tunnel servlet.", e);
            }
            catch (InvocationTargetException e) {
                logger.error("Internal error loading WebSocket tunnel servlet.", e);
            }

        }

        // If no such servlet class, WebSocket support not present
        catch (ClassNotFoundException e) {
            logger.info("WebSocket support not found.");
        }
        catch (NoClassDefFoundError e) {
            logger.info("WebSocket support not found.");
        }

        // Log all GuacamoleExceptions
        catch (GuacamoleException e) {
            logger.error("Unable to load/detect WebSocket support.", e);
        }

        // Load attempt failed
        return false;

    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {

        try {

            // Stop if WebSocket not explicitly enabled.
            if (!GuacamoleProperties.getProperty(ENABLE_WEBSOCKET, false)) {
                logger.info("WebSocket support not enabled.");
                return;
            }

        }
        catch (GuacamoleException e) {
            logger.error("Error parsing enable-websocket property.", e);
        }
        
        // Try to load websocket support for Jetty
        logger.info("Attempting to load Jetty-specific WebSocket support...");
        if (loadWebSocketTunnel(sce.getServletContext(), JETTY_WEBSOCKET))
            return;

        // Try to load websocket support for Tomcat
        logger.info("Attempting to load Tomcat-specific WebSocket support...");
        if (loadWebSocketTunnel(sce.getServletContext(), TOMCAT_WEBSOCKET))
            return;

        // Inform of lack of support
        logger.info("No WebSocket support could be loaded. Only HTTP will be used.");

    }

}

