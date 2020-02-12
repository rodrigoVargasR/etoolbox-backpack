
package com.exadel.aem.backpack.core.servlets;

import com.day.cq.commons.jcr.JcrConstants;
import com.exadel.aem.backpack.core.dto.response.PackageInfo;
import com.exadel.aem.backpack.core.dto.response.PackageStatus;
import com.exadel.aem.backpack.core.services.PackageService;
import com.google.gson.Gson;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.exadel.aem.backpack.core.servlets.BuildPackageServlet.APPLICATION_JSON;


@Component(service = Servlet.class,
		property = {
				"sling.servlet.paths=" + "/services/backpack/createPackage",
				"sling.servlet.methods=[post]",

		})
public class CreatePackageServlet extends SlingAllMethodsServlet {

	private static final long serialVersionUID = 1L;

	private static final Gson GSON = new Gson();
	private static final String PATHS = "paths";
	private static final String PACKAGE_NAME = "packageName";
	private static final String PACKAGE_GROUP = "packageGroup";
	private static final String VERSION = "version";
	private static final String JCR_CONTENT_NODE = "/" + JcrConstants.JCR_CONTENT;
	private static final String EXCLUDE_CHILDREN = "excludeChildren";


	@Reference
	private transient PackageService packageService;

	@Override
	protected void doPost(final SlingHttpServletRequest request,
						  final SlingHttpServletResponse response) throws IOException {
		String[] paths = request.getParameterValues(PATHS);

		String packageName = request.getParameter(PACKAGE_NAME);
		String packageGroup = request.getParameter(PACKAGE_GROUP);
		String version = request.getParameter(VERSION);
		boolean excludeChildren = BooleanUtils.toBoolean(request.getParameter(EXCLUDE_CHILDREN));
		List<String> actualPaths = Arrays.stream(paths)
				.map(path -> getActualPath(path, excludeChildren, request.getResourceResolver()))
				.collect(Collectors.toList());

		final PackageInfo packageInfo = packageService.createPackage(
				request.getResourceResolver(),
				actualPaths,
				packageName,
				packageGroup,
				version
		);

		response.setContentType(APPLICATION_JSON);
		response.getWriter().write(GSON.toJson(packageInfo));
		if (!PackageStatus.CREATED.equals(packageInfo.getPackageStatus())) {
			response.setStatus(HttpServletResponse.SC_CONFLICT);
		}
	}


	private String getActualPath(final String path, final boolean excludeChildren, ResourceResolver resourceResolver) {
		if (!excludeChildren) {
			return path;
		}
		Resource res = resourceResolver.getResource(path);
		if (res != null && res.getChild(JcrConstants.JCR_CONTENT) != null) {
			return path + JCR_CONTENT_NODE;
		}
		return path;
	}
}

