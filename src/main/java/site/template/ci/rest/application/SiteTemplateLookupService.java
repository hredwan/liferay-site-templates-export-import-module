package site.template.ci.rest.application;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.model.LayoutSetPrototype;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.service.LayoutSetPrototypeLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Validator;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.*;

@Component(service = SiteTemplateLookupService.class)
public class SiteTemplateLookupService {

    /**
     * Finds a LayoutSetPrototype (Site Template) by name, checking User Locale and Default Locale.
     * Optionally creates it if it doesn't exist.
     */
    public LayoutSetPrototype getOrCreate(User user, String templateName, boolean createIfMissing) throws PortalException {
        long companyId = user.getCompanyId();
        Locale userLocale = _resolveLocale(user);

        List<LayoutSetPrototype> prototypes = _layoutSetPrototypeLocalService.getLayoutSetPrototypes(companyId);

        // Filter matches
        List<LayoutSetPrototype> matches = prototypes.stream()
                .filter(lsp -> _matchesName(lsp, templateName, userLocale))
                .toList();

        if (matches.size() > 1) {
            String ids = matches.stream()
                    .map(lsp -> String.valueOf(lsp.getLayoutSetPrototypeId()))
                    .reduce((a, b) -> a + "," + b).orElse("");
            throw new IllegalArgumentException("Ambiguous template name '" + templateName + "'. Matches IDs: " + ids);
        }

        if (matches.isEmpty()) {
            if (!createIfMissing) {
                throw new NoSuchElementException("No Site Template found with name '" + templateName + "'");
            }
            return _create(user, templateName, userLocale);
        }

        return matches.getFirst();
    }

    private boolean _matchesName(LayoutSetPrototype lsp, String targetName, Locale userLocale) {
        String nameInUserLocale = lsp.getName(userLocale);
        String nameInDefLocale = lsp.getName(LocaleUtil.getDefault());

        return _equalsTrim(nameInUserLocale, targetName) || _equalsTrim(nameInDefLocale, targetName);
    }

    private boolean _equalsTrim(String a, String b) {
        return Validator.isNotNull(a) && Validator.isNotNull(b) && a.trim().equalsIgnoreCase(b.trim());
    }

    private LayoutSetPrototype _create(User user, String name, Locale locale) throws PortalException {
        ServiceContext sc = new ServiceContext();
        sc.setCompanyId(user.getCompanyId());
        sc.setUserId(user.getUserId());

        Map<Locale, String> nameMap = new HashMap<>();
        nameMap.put(LocaleUtil.getDefault(), name);
        nameMap.put(locale, name);

        return _layoutSetPrototypeLocalService.addLayoutSetPrototype(
                user.getUserId(),
                user.getCompanyId(),
                nameMap,
                Map.of(LocaleUtil.getDefault(), "Created by CI/CD"),
                true, // active
                true, // layoutsUpdateable
                sc
        );
    }

    private Locale _resolveLocale(User user) {
        Locale l = user.getLocale();
        return (l != null) ? l : LocaleUtil.getDefault();
    }

    @Reference
    private LayoutSetPrototypeLocalService _layoutSetPrototypeLocalService;
}