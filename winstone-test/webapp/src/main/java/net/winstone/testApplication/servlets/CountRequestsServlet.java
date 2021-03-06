/*
 * Copyright 2003-2006 Rick Knowles <winstone-devel at lists sourceforge net>
 * Distributed under the terms of either:
 * - the common development and distribution license (CDDL), v1.0; or
 * - the GNU Lesser General Public License, v2.1 or later
 */
package net.winstone.testApplication.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple test servlet that counts the number of times it has been requested,
 * and returns that number in the response.
 * 
 * @author <a href="mailto:rick_knowles@hotmail.com">Rick Knowles</a>
 * @version $Id: CountRequestsServlet.java,v 1.3 2006/02/28 07:32:49 rickknowles
 *          Exp $
 */
public class CountRequestsServlet extends HttpServlet {
	private Logger logger = LoggerFactory.getLogger(CountRequestsServlet.class);
	private static final long serialVersionUID = -1276193379275847349L;
	private int numberOfGets;

	@Override
	public void init() {
		final String offset = getServletConfig().getInitParameter("offset");
		numberOfGets = offset == null ? 0 : Integer.parseInt(offset);
	}

	/**
	 * Get implementation - increments and shows the access count
	 */
	@Override
	protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
		logger.trace("CountRequestsServlet/doGet");
		numberOfGets++;
		final ServletOutputStream out = response.getOutputStream();
		out.println("<html><body>This servlet has been accessed via GET " + numberOfGets + " times</body></html>");
		out.flush();
	}
}
