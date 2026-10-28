package com.genericlauncher.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The manifest's package-visibility {@code <queries>} must let the app see other HOME apps.
 *
 * <p>Without a MAIN/HOME query, Android 11+ hides every other launcher from this app, and
 * {@code resolveActivity(HOME)} returns the only candidate it can see — this app. So
 * {@link DefaultLauncherHelper#isDefault} said "default" on an emulator whose home was the stock
 * launcher (2026-09-07), and the set-up card ticked "Set as default home" off unearned. Robolectric
 * does not enforce package visibility, so no test of {@code isDefault} can see this; the manifest
 * is the only place it is expressible.
 */
public class ManifestQueriesTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void theManifestQueriesOtherHomeApps() throws Exception {
        File manifest = new File("src/main/AndroidManifest.xml");
        assertTrue("manifest not found from " + new File(".").getAbsolutePath(), manifest.exists());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);   // or getAttributeNS(android:name) reads as empty
        Document doc = factory.newDocumentBuilder().parse(manifest);

        boolean found = false;
        NodeList queries = doc.getElementsByTagName("queries");
        for (int q = 0; q < queries.getLength(); q++) {
            NodeList intents = ((Element) queries.item(q)).getElementsByTagName("intent");
            for (int i = 0; i < intents.getLength(); i++) {
                Element intent = (Element) intents.item(i);
                if (hasChildNamed(intent, "action", "android.intent.action.MAIN")
                        && hasChildNamed(intent, "category", "android.intent.category.HOME")) {
                    found = true;
                }
            }
        }
        assertTrue("no <queries><intent> for MAIN + HOME: isDefault() cannot see other launchers", found);
    }

    private static boolean hasChildNamed(Element intent, String tag, String name) {
        NodeList kids = intent.getElementsByTagName(tag);
        for (int k = 0; k < kids.getLength(); k++) {
            if (name.equals(((Element) kids.item(k)).getAttributeNS(ANDROID_NS, "name"))) return true;
        }
        return false;
    }
}
