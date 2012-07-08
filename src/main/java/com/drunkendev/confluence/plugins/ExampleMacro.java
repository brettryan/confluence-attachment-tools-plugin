package com.drunkendev.confluence.plugins;

import java.util.Map;
import java.util.List;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.user.User;
import com.opensymphony.util.TextUtils;

/**
 * This very simple macro shows you the very basic use-case of displaying *something* on the Confluence page where it is used.
 * Use this example macro to toy around, and then quickly move on to the next example - this macro doesn't
 * really show you all the fun stuff you can do with Confluence.
 */
public class ExampleMacro implements Macro
{

    // We just have to define the variables and the setters, then Spring injects the correct objects for us to use. Simple and efficient.
    // You just need to know *what* you want to inject and use.

    private final PageManager pageManager;
    private final SpaceManager spaceManager;

    public ExampleMacro(PageManager pageManager, SpaceManager spaceManager)
    {
        this.pageManager = pageManager;
        this.spaceManager = spaceManager;
    }

    /**
     * This method returns XHTML to be displayed on the page that uses this macro
     * we just do random stuff here, trying to show how you can access the most basic
     * managers and model objects. No emphasis is put on beauty of code nor on
     * doing actually useful things :-)
     */
    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context) throws MacroExecutionException
    {
        // in this most simple example, we build the result in memory, appending HTML code to it at will.
        // this is something you absolutely don't want to do once you start writing plugins for real. Refer
        // to the next example for better ways to render content.
        StringBuffer result = new StringBuffer();

        // get the currently logged in user and display his name
        User user = AuthenticatedUserThreadLocal.getUser();
        if (user != null)
        {
            String greeting = "<p>Hello <b>" + TextUtils.htmlEncode(user.getFullName()) + "</b>.</p>";
            result.append(greeting);
        }

        //get the pages added in the last 55 days to the DS space ("Demo Space"), and display them
        List<Page> list = pageManager.getRecentlyAddedPages(55, "DS");
        result.append("<p>");
        result.append("Some stats for <b>Demonstration Space</b>:");
        result.append("<table class=\"confluenceTable\">");
        result.append("<thead><tr><th class=\"confluenceTh\">Page</th><th class=\"confluenceTh\">Number of children</th></tr></thead>");
        result.append("<tbody>");
        for (Page page : list)
        {
            int numberOfChildren = page.getChildren().size();
            String pageWithChildren = "<tr><td class=\"confluenceTd\">" + TextUtils.htmlEncode(page.getTitle()) + "</td><td class=\"confluenceTd\" style=\"text-align:right\">" + numberOfChildren + "</td></tr>";
            result.append(pageWithChildren);
        }
        result.append("</tbody>");
        result.append("</table>");
        result.append("</p>");

        // and show the number of all spaces in this installation.
        String spaces = "<p>Altogether, this installation has <b>" + spaceManager.getAllSpaces().size() + "</b> spaces.</p>";
        result.append(spaces);

        // this concludes our little demo. Now you should understand the basics of code injection use in Confluence, and how
        // to get a really simple macro running.

        return result.toString();
    }

    @Override
    public BodyType getBodyType()
    {
        return BodyType.NONE;
    }

    @Override
    public OutputType getOutputType()
    {
        return OutputType.BLOCK;
    }

}
