/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.cris.configuration;

import java.sql.SQLException;

import org.apache.log4j.Logger;
import org.dspace.app.cris.util.CrisAuthorizeManager;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.RootObject;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.springframework.beans.factory.annotation.Autowired;

public class AdminSecurityCheck implements SecurityCheck {

    private static Logger log = Logger.getLogger(AdminSecurityCheck.class);
    
    @Autowired
    private AuthorizeService authorizeService;

    public boolean isAuthorized(Context context, RootObject dso) {
        if (context != null) {
            EPerson currentUser = context.getCurrentUser();
            if(currentUser == null) {
                return false;
            }

            try {
                if (CrisAuthorizeManager.isAdmin(context, dso)) {
                    return true;
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
        }

        return false;
    }

}
