package com.smartbear.ready.plugin.jira.actions;

import com.eviware.loadui.ui.actions.LoadUIActionGroups;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.plugins.ActionConfiguration;

/**
 * Created by avdeev on 30.03.2015.
 */
@ActionConfiguration(actionGroup = LoadUIActionGroups.LOADUI_MODULE_TOOLBAR_ACTIONS, targetType = ModelItem.class, isToolbarAction = true,
        iconPath = "com/smartbear/ready/plugin/jira/icons/Bug-tracker-icon_20-20-px.png")
public class LoadUINGAction extends CreateNewBugAction{
}
