package site.template.ci.rest.application;

import com.liferay.exportimport.kernel.model.ExportImportConfiguration;
import com.liferay.exportimport.kernel.service.ExportImportLocalService;
import com.liferay.petra.io.StreamUtil;
import com.liferay.portal.background.task.model.BackgroundTask;
import com.liferay.portal.background.task.service.BackgroundTaskLocalService;
import com.liferay.portal.kernel.backgroundtask.constants.BackgroundTaskConstants;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.model.LayoutSetPrototype;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.security.auth.PrincipalThreadLocal;
import com.liferay.portal.kernel.service.LayoutLocalService;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.util.Validator;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(
        service = SiteTemplateCIResource.class,
        property = {
                "osgi.jaxrs.resource=true",
                "osgi.jaxrs.application.select=(osgi.jaxrs.name=SiteTemplateCI)"
        }
)
@Path("/v1")
public class SiteTemplateCIResource {

    @POST
    @Path("/site-templates/export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response exportSiteTemplate(
            @QueryParam("templateName") String templateName
    ) throws Exception {

        User user = _getAuthenticatedUser();
        if (Validator.isNull(templateName)) {
            return Response.status(400).entity("Missing templateName").build();
        }

        try {
            // 1. Find the Template
            LayoutSetPrototype lsp = _siteTemplateLookupService.getOrCreate(user, templateName, false);
            long groupId = lsp.getGroupId();

            // 2. Get Layouts
            List<Layout> layouts = _layoutLocalService.getLayouts(groupId, true);
            long[] layoutIds = layouts.stream().mapToLong(Layout::getLayoutId).toArray();

            // 3. Create Config
            ExportImportConfiguration config = _ciExportImportService.createExportConfig(
                    user, groupId, layoutIds, "CI Export-" + templateName
            );

            // 4. Trigger
            long taskId = _exportImportLocalService.exportLayoutsAsFileInBackground(user.getUserId(), config);

            return Response.ok(Map.of("backgroundTaskId", taskId, "layoutCount", layoutIds.length)).build();

        } catch (NoSuchElementException e) {
            return Response.status(404).entity(e.getMessage()).build();
        } catch (IllegalArgumentException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/site-templates/import")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importSiteTemplate(
            @QueryParam("templateName") String templateName,
            @QueryParam("privateLayout") @DefaultValue("true") boolean privateLayout,
            @QueryParam("validate") @DefaultValue("true") boolean validate,
            @QueryParam("force") @DefaultValue("false") boolean force,
            @QueryParam("createIfMissing") @DefaultValue("false") boolean createIfMissing,
            InputStream larInputStream
    ) throws Exception {

        User user = _getAuthenticatedUser();
        if (Validator.isNull(templateName) || larInputStream == null) {
            return Response.status(400).entity("Missing templateName or LAR body").build();
        }

        // 1. Resolve Group
        LayoutSetPrototype lsp = _siteTemplateLookupService.getOrCreate(user, templateName, createIfMissing);
        long groupId = lsp.getGroupId();

        // 2. Prepare Config
        ExportImportConfiguration config = _ciExportImportService.createImportConfig(
                user, groupId, privateLayout, "CI Import: " + templateName
        );

        // 3. Buffer Stream (needed for potentially 2 reads: validate + import)
        byte[] larBytes = StreamUtil.toByteArray(larInputStream);

        // 4. Validate (Optional)
        Map<String, Object> validationResult = new HashMap<>();
        if (validate) {
            var missing = _exportImportLocalService.validateImportLayoutsFile(config, new ByteArrayInputStream(larBytes));
            boolean hasIssues = (missing != null) && !missing.getDependencyMissingReferences().isEmpty();

            validationResult.put("hasMissingReferences", hasIssues);
            if (hasIssues && !force) {
                return Response.status(409).entity(Map.of(
                        "error", "Validation failed. Use force=true to proceed.",
                        "missingReferences", missing.getDependencyMissingReferences().size()
                )).build();
            }
        }

        // 5. Trigger Import
        long taskId = _exportImportLocalService.importLayoutsInBackground(
                user.getUserId(), config, new ByteArrayInputStream(larBytes)
        );

        return Response.ok(Map.of(
                "backgroundTaskId", taskId,
                "templateGroupId", groupId,
                "validation", validationResult
        )).build();
    }

    @GET
    @Path("/background-tasks/{backgroundTaskId}/lar")
    @Produces("application/zip")
    public Response downloadLar(@PathParam("backgroundTaskId") long backgroundTaskId) throws Exception {
        BackgroundTask task = _backgroundTaskLocalService.getBackgroundTask(backgroundTaskId);

        if (!task.isCompleted()) return Response.status(409).entity("Task not completed").build();
        if (task.getStatus() != BackgroundTaskConstants.STATUS_SUCCESSFUL) return Response.status(500).entity("Task failed").build();

        List<FileEntry> attachments = task.getAttachmentsFileEntries();
        FileEntry larFile = attachments.stream()
                .filter(f -> f.getFileName().endsWith(".lar"))
                .max(Comparator.comparingLong(FileEntry::getSize))
                .orElseThrow(() -> new WebApplicationException("No LAR found", 404));

        StreamingOutput output = out -> {
            try (InputStream in = larFile.getContentStream()) {
                StreamUtil.transfer(in, out);
            } catch (PortalException e) {
                throw new RuntimeException(e);
            }
        };

        return Response.ok(output)
                .header("Content-Disposition", "attachment; filename=\"" + larFile.getFileName() + "\"")
                .header("Content-Length", larFile.getSize())
                .build();
    }

    @GET
    @Path("/background-tasks/{backgroundTaskId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus(@PathParam("backgroundTaskId") long backgroundTaskId) throws PortalException {
        BackgroundTask task = _backgroundTaskLocalService.getBackgroundTask(backgroundTaskId);
        return Response.ok(Map.of(
                "status", task.getStatus(),
                "completed", task.isCompleted(),
                "statusMessage", String.valueOf(task.getStatusMessage())
        )).build();
    }

    private User _getAuthenticatedUser() throws PortalException {
        long userId = PrincipalThreadLocal.getUserId();
        if (userId <= 0) throw new WebApplicationException("Unauthorized", 401);
        return _userLocalService.getUser(userId);
    }

    @Reference private SiteTemplateLookupService _siteTemplateLookupService;
    @Reference private CIExportImportService _ciExportImportService;
    @Reference private ExportImportLocalService _exportImportLocalService;
    @Reference private BackgroundTaskLocalService _backgroundTaskLocalService;
    @Reference private UserLocalService _userLocalService;
    @Reference private LayoutLocalService _layoutLocalService;
}