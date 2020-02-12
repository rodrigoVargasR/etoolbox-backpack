package com.exadel.aem.backpack.core.services.impl;

import com.day.cq.commons.jcr.JcrUtil;
import com.exadel.aem.backpack.core.dto.repository.AssetReferencedItem;
import com.exadel.aem.backpack.core.dto.response.PackageInfo;
import com.exadel.aem.backpack.core.dto.response.PackageStatus;
import com.exadel.aem.backpack.core.services.PackageService;
import com.exadel.aem.backpack.core.services.ReferenceService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.jackrabbit.vault.fs.api.FilterSet;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.api.WorkspaceFilter;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.packaging.*;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component(service = PackageService.class)
@Designate(ocd = PackageServiceImpl.Configuration.class)
public class PackageServiceImpl implements PackageService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PackageServiceImpl.class);


	private static final String DEFAULT_PACKAGE_GROUP = "backpack";
	private static final String THUMBNAIL_PATH = "/apps/backpack/assets/backpack.png";
	private static final String THUMBNAIL_FILE = "thumbnail.png";
	private static final String ERROR = "ERROR: ";
	private static final Gson GSON = new Gson();
	private static final String REFERENCED_RESOURCES = "referencedResources";
	private static final String GENERAL_RESOURCES = "generalResources";

	@Reference
	private ReferenceService referenceService;

	@Reference
	private SlingRepository slingRepository;

	private Cache<String, PackageInfo> packagesInfos;

	@Activate
	private void activate(Configuration config) {
		packagesInfos = CacheBuilder.newBuilder()
				.maximumSize(100)
				.expireAfterWrite(config.buildInfoTTL(), TimeUnit.DAYS)
				.build();
	}

	@ObjectClassDefinition(name = "BackPack PackageService configuration")
	public @interface Configuration {
		@AttributeDefinition(
				name = "Package Build Info TTL",
				description = "Specify TTL for package build information cache (in days).",
				type = AttributeType.INTEGER
		)
		int buildInfoTTL() default 1;
	}

	@Override
	public PackageInfo testBuildPackage(final ResourceResolver resourceResolver,
										final String packagePath,
										final Collection<String> referencedResources) {
		PackageInfo packageInfo = new PackageInfo();

		final Session session = resourceResolver.adaptTo(Session.class);
		if (session == null) {
			packageInfo.addLogMessage(ERROR + " session is null");
			return packageInfo;
		}
		JcrPackageManager packMgr = PackagingService.getPackageManager(session);
		Node packageNode;
		JcrPackage jcrPackage = null;
		AtomicLong totalSize = new AtomicLong();

		try {
			packageNode = session.getNode(packagePath);

			if (packageNode != null) {
				jcrPackage = packMgr.open(packageNode);
				if (jcrPackage != null) {
					JcrPackageDefinition definition = jcrPackage.getDefinition();
					if (definition == null) {
						packageInfo.addLogMessage(ERROR + " package definition is null");
						return packageInfo;
					}
					PackageInfo finalPackageInfo = packageInfo;
					includeGeneralResources(definition, s -> finalPackageInfo.addLogMessage("A " + s));
					includeReferencedResources(referencedResources, definition, s -> {
						finalPackageInfo.addLogMessage("A " + s);
						totalSize.addAndGet(getAssetSize(resourceResolver, s));
					});
					packageInfo.setDataSize(totalSize.get());
					packageInfo.setPackageBuilt(definition.getLastWrapped());
				}
			}
		} catch (RepositoryException e) {
			LOGGER.error("Error during package opening", e);
		} finally {
			if (jcrPackage != null) {
				jcrPackage.close();
			}
		}
		return packageInfo;
	}

	private Long getAssetSize(ResourceResolver resourceResolver, String path) {
		Resource rootResource = resourceResolver.getResource(path);
		return getAssetSize(rootResource);
	}

	private Long getAssetSize(Resource resource) {
		Long totalSize = 0l;
		if (resource == null) {
			return totalSize;
		}
		for (Resource child : resource.getChildren()) {
			totalSize += getAssetSize(child);
		}
		Resource childResource = resource.getChild("jcr:content/jcr:data");
		if (childResource != null && childResource.getResourceMetadata().containsKey("sling.contentLength")) {
			totalSize += (Long) childResource.getResourceMetadata().get("sling.contentLength");
		}
		return totalSize;
	}

	@Override
	public PackageInfo buildPackage(final ResourceResolver resourceResolver,
									final String packagePath,
									final Collection<String> referencedResources) {
		PackageInfo packageInfo = getPackageInfo(resourceResolver, packagePath);
		if (!PackageStatus.BUILD_IN_PROGRESS.equals(packageInfo.getPackageStatus())) {
			packageInfo.setPackageStatus(PackageStatus.BUILD_IN_PROGRESS);
			packageInfo.clearLog();
			packagesInfos.put(packagePath, packageInfo);
			buildPackage(resourceResolver.getUserID(), packageInfo, referencedResources);
		}

		return packageInfo;
	}

	@Override
	public PackageInfo createPackage(final ResourceResolver resourceResolver, final Collection<String> initialPaths, final String pkgName, final String packageGroup, final String version) {
		final Session session = resourceResolver.adaptTo(Session.class);

		JcrPackageManager packMgr = PackagingService.getPackageManager(session);
		PackageInfo packageInfo = new PackageInfo();
		packageInfo.setPackageName(pkgName);
		packageInfo.setPaths(initialPaths);
		packageInfo.setVersion(version);
		packageInfo.setThumbnailPath(THUMBNAIL_PATH);

		String pkgGroupName = DEFAULT_PACKAGE_GROUP;

		if (StringUtils.isNotBlank(packageGroup)) {
			pkgGroupName = packageGroup;
		}
		packageInfo.setGroupName(pkgGroupName);
		try {
			if (isPkgExists(packMgr, pkgName, pkgGroupName, version)) {
				String packageExistMsg = "Package with such name already exist in the " + pkgGroupName + " group.";

				packageInfo.addLogMessage(ERROR + packageExistMsg);
				LOGGER.error(packageExistMsg);
				return packageInfo;
			}
		} catch (RepositoryException e) {
			packageInfo.addLogMessage(ERROR + e.getMessage());
			packageInfo.addLogMessage(ExceptionUtils.getStackTrace(e));
			LOGGER.error("Error during existing packages check", e);
			return packageInfo;
		}

		Set<AssetReferencedItem> referencedAssets = getReferencedAssets(resourceResolver, initialPaths);
		Collection<String> resultingPaths = initAssets(initialPaths, referencedAssets, packageInfo);
		DefaultWorkspaceFilter filter = getWorkspaceFilter(resultingPaths);
		createPackage(resourceResolver, packageInfo, filter);

		return packageInfo;
	}

	@Override
	public PackageInfo getPackageInfo(final ResourceResolver resourceResolver, final String pathToPackage) {
		PackageInfo packageInfo = packagesInfos.asMap().get(pathToPackage);
		if (packageInfo != null) {
			return packageInfo;
		}

		final Session session = resourceResolver.adaptTo(Session.class);
		JcrPackageManager packMgr = PackagingService.getPackageManager(session);

		packageInfo = new PackageInfo();

		JcrPackage jcrPackage = null;

		try {
			if (!isPkgExists(packMgr, pathToPackage)) {
				packageNotExistInfo(pathToPackage, packageInfo);
			} else if (session != null) {
				Node packageNode = session.getNode(pathToPackage);
				if (packageNode != null) {
					jcrPackage = packMgr.open(packageNode);
					packageExistInfo(packageInfo, jcrPackage, packageNode);
				}
			}
		} catch (RepositoryException e) {
			LOGGER.error("Error during package opening", e);
		} finally {
			if (jcrPackage != null) {
				jcrPackage.close();
			}
		}
		return packageInfo;
	}

	private void packageExistInfo(final PackageInfo packageInfo, final JcrPackage jcrPackage, final Node packageNode) throws RepositoryException {
		if (jcrPackage != null) {
			JcrPackageDefinition definition = jcrPackage.getDefinition();
			if (definition != null) {
				WorkspaceFilter filter = definition.getMetaInf().getFilter();
				if (filter != null) {
					List<PathFilterSet> filterSets = filter.getFilterSets();
					packageInfo.setPackagePath(packageNode.getPath());
					packageInfo.setPackageName(definition.get(JcrPackageDefinition.PN_NAME));
					packageInfo.setGroupName(definition.get(JcrPackageDefinition.PN_GROUP));
					packageInfo.setVersion(definition.get(JcrPackageDefinition.PN_VERSION));
					packageInfo.setReferencedResources(GSON.fromJson(definition.get(REFERENCED_RESOURCES), Map.class));
					packageInfo.setPaths(filterSets.stream().map(FilterSet::getRoot).collect(Collectors.toList()));
					packageInfo.setDataSize(jcrPackage.getSize());
					packageInfo.setPackageBuilt(definition.getLastWrapped());
					if (definition.getLastWrapped() != null) {
						packageInfo.setPackageStatus(PackageStatus.BUILT);
					} else {
						packageInfo.setPackageStatus(PackageStatus.CREATED);
					}
					packageInfo.setPackageNodeName(packageNode.getName());
				}
			}
		}
	}

	private void packageNotExistInfo(final String pathToPackage, final PackageInfo buildInfo) {
		String packageExistMsg = "Package by this path " + pathToPackage + " doesn't exist in the repository.";
		buildInfo.setPackagePath(pathToPackage);
		buildInfo.addLogMessage(ERROR + packageExistMsg);
		LOGGER.error(packageExistMsg);
	}


	private Collection<String> initAssets(final Collection<String> initialPaths,
										  final Set<AssetReferencedItem> referencedAssets,
										  final PackageInfo packageInfo) {
		Collection<String> resultingPaths = new ArrayList<>(initialPaths);
		referencedAssets.forEach(packageInfo::addAssetReferencedItem);
		return resultingPaths;
	}

	@Override
	public PackageInfo getLatestPackageBuildInfo(final String packagePath, final int latestLogIndex) {
		PackageInfo completeBuildInfo = packagesInfos.asMap().get(packagePath);
		PackageInfo partialBuildInfo = null;

		if (completeBuildInfo != null) {
			partialBuildInfo = new PackageInfo(completeBuildInfo);
			partialBuildInfo.setBuildLog(completeBuildInfo.getLatestBuildInfo(latestLogIndex));
		}
		return partialBuildInfo;
	}

	private JcrPackage createPackage(final ResourceResolver resourceResolver,
									 final PackageInfo packageBuildInfo,
									 final DefaultWorkspaceFilter filter) {
		Session userSession = null;
		JcrPackage jcrPackage = null;
		try {
			userSession = slingRepository.loginAdministrative(null);
			userSession.impersonate(new SimpleCredentials(resourceResolver.getUserID(), new char[0]));
			JcrPackageManager packMgr = PackagingService.getPackageManager(userSession);
			if (!filter.getFilterSets().isEmpty()) {
				jcrPackage = packMgr.create(packageBuildInfo.getGroupName(), packageBuildInfo.getPackageName(), packageBuildInfo.getVersion());
				JcrPackageDefinition jcrPackageDefinition = jcrPackage.getDefinition();
				if (jcrPackageDefinition != null) {
					jcrPackageDefinition.set(REFERENCED_RESOURCES, GSON.toJson(packageBuildInfo.getReferencedResources()), true);
					jcrPackageDefinition.set(GENERAL_RESOURCES, GSON.toJson(packageBuildInfo.getPaths()), true);
					jcrPackageDefinition.setFilter(filter, true);

					Node packageNode = jcrPackage.getNode();
					if (packageNode != null) {
						packageBuildInfo.setPackageNodeName(packageNode.getName());
					}
					addThumbnail(jcrPackageDefinition.getNode(), getThumbnailNode(packageBuildInfo.getThumbnailPath(), resourceResolver));
					jcrPackage.close();
				}
			}

			packageBuildInfo.setPackageStatus(PackageStatus.CREATED);
		} catch (Exception e) {
			packageBuildInfo.setPackageStatus(PackageStatus.ERROR);
			packageBuildInfo.addLogMessage(ERROR + e.getMessage());
			packageBuildInfo.addLogMessage(ExceptionUtils.getStackTrace(e));
			LOGGER.error("Error during package creation", e);
		} finally {
			closeSession(userSession);
		}
		return jcrPackage;
	}

	private void buildPackage(final String userId, final PackageInfo packageBuildInfo, final Collection<String> referencedResources) {
		new Thread(() -> {
			Session userSession = null;
			try {
				userSession = slingRepository.loginAdministrative(null);
				userSession.impersonate(new SimpleCredentials(userId, new char[0]));
				JcrPackageManager packMgr = PackagingService.getPackageManager(userSession);
				JcrPackage jcrPackage = packMgr.open(userSession.getNode(packageBuildInfo.getPackagePath()));
				JcrPackageDefinition definition = jcrPackage.getDefinition();
				DefaultWorkspaceFilter filter = new DefaultWorkspaceFilter();
				includeGeneralResources(definition, s -> filter.add(new PathFilterSet(s)));
				includeReferencedResources(referencedResources, definition, s -> filter.add(new PathFilterSet(s)));
				definition.setFilter(filter, true);
				packageBuildInfo.setPackageStatus(PackageStatus.BUILD_IN_PROGRESS);
				packMgr.assemble(jcrPackage, new ProgressTrackerListener() {
					@Override
					public void onMessage(final Mode mode, final String statusCode, final String path) {
						packageBuildInfo.addLogMessage(statusCode + " " + path);
					}

					@Override
					public void onError(final Mode mode, final String s, final Exception e) {
						packageBuildInfo.addLogMessage(s + " " + e.getMessage());
					}
				});
				packageBuildInfo.setPackageBuilt(Calendar.getInstance());
				packageBuildInfo.setPackageStatus(PackageStatus.BUILT);
			} catch (Exception e) {
				packageBuildInfo.setPackageStatus(PackageStatus.ERROR);
				packageBuildInfo.addLogMessage(ERROR + e.getMessage());
				packageBuildInfo.addLogMessage(ExceptionUtils.getStackTrace(e));
				LOGGER.error("Error during package generation", e);
			} finally {
				closeSession(userSession);
			}
		}).start();
	}

	private void includeGeneralResources(final JcrPackageDefinition definition, final Consumer<String> pathConsumer) {
		List<String> pkgGeneralResources = (List<String>) GSON.fromJson(definition.get(GENERAL_RESOURCES), List.class);
		if (pkgGeneralResources != null) {
			pkgGeneralResources.forEach(pathConsumer::accept);
		}
	}

	private void includeReferencedResources(final Collection<String> referencedResources, final JcrPackageDefinition definition, final Consumer<String> pathConsumer) {
		Map<String, List<String>> pkgReferencedResources = (Map<String, List<String>>) GSON.fromJson(definition.get(REFERENCED_RESOURCES), Map.class);

		if (pkgReferencedResources != null) {
			List<String> includeResources = referencedResources.stream()
					.map(pkgReferencedResources::get)
					.flatMap(List::stream)
					.collect(Collectors.toList());
			includeResources.forEach(pathConsumer);
		}
	}

	private void closeSession(final Session userSession) {
		if (userSession != null && userSession.isLive()) {
			userSession.logout();
		}
	}

	private Set<AssetReferencedItem> getReferencedAssets(final ResourceResolver resourceResolver, final Collection<String> paths) {
		Set<AssetReferencedItem> assetLinks = new HashSet<>();
		paths.forEach(path -> {
			Set<AssetReferencedItem> assetReferences = referenceService.getAssetReferences(resourceResolver, path);
			assetLinks.addAll(assetReferences);
		});
		return assetLinks;
	}

	private DefaultWorkspaceFilter getWorkspaceFilter(final Collection<String> paths) {
		DefaultWorkspaceFilter filter = new DefaultWorkspaceFilter();
		paths.forEach(path -> {
			PathFilterSet pathFilterSet = new PathFilterSet(path);
			filter.add(pathFilterSet);
		});

		return filter;
	}

	private boolean isPkgExists(final JcrPackageManager pkgMgr,
								final String newPkgName,
								final String pkgGroupName,
								final String version) throws RepositoryException {
		List<JcrPackage> packages = pkgMgr.listPackages(pkgGroupName, false);
		for (JcrPackage jcrpackage : packages) {
			JcrPackageDefinition definition = jcrpackage.getDefinition();
			if (definition != null) {
				String packageName = definition.getId().toString();
				if (packageName.equalsIgnoreCase(getPackageId(pkgGroupName, newPkgName, version))) {
					return true;
				}
			}
		}
		return false;
	}


	private boolean isPkgExists(final JcrPackageManager pkgMgr,
								final String path) throws RepositoryException {
		List<JcrPackage> packages = pkgMgr.listPackages();
		for (JcrPackage jcrpackage : packages) {
			Node packageNode = jcrpackage.getNode();
			if (packageNode != null) {
				String packagePath = packageNode.getPath();
				if (packagePath.equals(path)) {
					return true;
				}
			}
		}
		return false;
	}

	private String getPackageId(final String pkgGroupName, final String packageName, final String version) {
		return pkgGroupName + ":" + packageName + (StringUtils.isNotBlank(version) ? ":" + version : StringUtils.EMPTY);
	}

	private void addThumbnail(Node packageNode, final Node thumbnailNode) {
		if (packageNode == null || thumbnailNode == null) {
			LOGGER.warn("Could not add package thumbnail");
			return;
		}
		try {
			JcrUtil.copy(thumbnailNode, packageNode, THUMBNAIL_FILE);
			packageNode.getSession().save();
		} catch (RepositoryException e) {
			LOGGER.error("A repository exception occurred: ", e);
		}
	}

	private Node getThumbnailNode(final String thumbnailPath, final ResourceResolver resourceResolver) {
		Resource thumbnailResource = resourceResolver.getResource(thumbnailPath);
		if (thumbnailResource != null && !ResourceUtil.isNonExistingResource(thumbnailResource)) {
			return thumbnailResource.adaptTo(Node.class);
		}
		return null;
	}

}



