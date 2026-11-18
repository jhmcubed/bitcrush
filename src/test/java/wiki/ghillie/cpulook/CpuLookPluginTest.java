package wiki.ghillie.cpulook;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// RuneLite with Bitcrush loaded, for trying it from this repository
public class CpuLookPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CpuLookPlugin.class);
		RuneLite.main(args);
	}
}
