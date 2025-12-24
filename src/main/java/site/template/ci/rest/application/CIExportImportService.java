package site.template.ci.rest.application;

import com.liferay.exportimport.kernel.configuration.ExportImportConfigurationParameterMapFactoryUtil;
import com.liferay.exportimport.kernel.configuration.ExportImportConfigurationSettingsMapFactory;
import com.liferay.exportimport.kernel.configuration.constants.ExportImportConfigurationConstants;
import com.liferay.exportimport.kernel.lar.PortletDataHandlerKeys;
import com.liferay.exportimport.kernel.lar.StagedModelDataHandler;
import com.liferay.exportimport.kernel.lar.StagedModelDataHandlerRegistryUtil;
import com.liferay.exportimport.kernel.model.ExportImportConfiguration;
import com.liferay.exportimport.kernel.service.ExportImportConfigurationLocalService;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Component(service = CIExportImportService.class)
public class CIExportImportService {

    public ExportImportConfiguration createExportConfig(User user, long groupId, long[] layoutIds, String name) throws Exception {
        Map<String, String[]> parameterMap = ExportImportConfigurationParameterMapFactoryUtil.buildFullPublishParameterMap();

        // Enforce Site Template specific settings
        parameterMap.put(PortletDataHandlerKeys.LAYOUT_SET_PROTOTYPE_SETTINGS, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.LAYOUT_SET_SETTINGS, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.THEME_REFERENCE, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.LOGO, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.PORTLET_SETUP_ALL, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.PORTLET_CONFIGURATION_ALL, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.PORTLET_DATA_ALL, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.PERMISSIONS, new String[]{"true"});

        Map<String, Serializable> settingsMap = _settingsMapFactory.buildExportLayoutSettingsMap(
                user.getUserId(), groupId, true, layoutIds, parameterMap, user.getLocale(), user.getTimeZone()
        );

        return _createConfig(user, groupId, name, "CI Export", ExportImportConfigurationConstants.TYPE_EXPORT_LAYOUT, settingsMap);
    }

    public ExportImportConfiguration createImportConfig(User user, long groupId, boolean privateLayout, String name) throws Exception {
        // Build robust import map
        Map<String, String[]> parameterMap = new HashMap<>(ExportImportConfigurationParameterMapFactoryUtil.buildFullPublishParameterMap());

        parameterMap.put(PortletDataHandlerKeys.DATA_STRATEGY, new String[]{PortletDataHandlerKeys.DATA_STRATEGY_MIRROR_OVERWRITE});
        parameterMap.put(PortletDataHandlerKeys.PERMISSIONS, new String[]{"true"});
        parameterMap.put(PortletDataHandlerKeys.DELETIONS, new String[]{"false"}); // Usually safer for Templates

        // Enable all Staged Models (critical for comprehensive imports)
        for (StagedModelDataHandler<?> handler : StagedModelDataHandlerRegistryUtil.getStagedModelDataHandlers()) {
            parameterMap.put(handler.getClass().getName(), new String[]{"true"});
        }

        Map<String, Serializable> settingsMap = _settingsMapFactory.buildImportLayoutSettingsMap(
                user, groupId, privateLayout, null, parameterMap
        );

        return _createConfig(user, groupId, name, "CI Import", ExportImportConfigurationConstants.TYPE_IMPORT_LAYOUT, settingsMap);
    }

    private ExportImportConfiguration _createConfig(User user, long groupId, String name, String desc, int type, Map<String, Serializable> settings) throws Exception {
        ServiceContext sc = new ServiceContext();
        sc.setCompanyId(user.getCompanyId());
        sc.setUserId(user.getUserId());
        sc.setScopeGroupId(groupId);

        return _exportImportConfigurationLocalService.addExportImportConfiguration(
                user.getUserId(), groupId, name, desc, type, settings, WorkflowConstants.STATUS_APPROVED, sc
        );
    }

    @Reference private ExportImportConfigurationSettingsMapFactory _settingsMapFactory;
    @Reference private ExportImportConfigurationLocalService _exportImportConfigurationLocalService;
}