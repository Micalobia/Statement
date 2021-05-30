package virtuoel.statement.api;

import java.util.function.Supplier;

import com.google.gson.JsonObject;

import virtuoel.statement.util.JsonConfigHandler;

public class StatementConfig
{
	public static final Supplier<JsonObject> HANDLER =
		new JsonConfigHandler(
			StatementApi.MOD_ID,
			"config.json",
			StatementConfig::createDefaultConfig
		);
	
	public static final JsonObject DATA = HANDLER.get();
	
	private static JsonObject createDefaultConfig()
	{
		final JsonObject config = new JsonObject();
		
		config.add("customBlockStateDeferral", new JsonObject());
		
		config.addProperty("enableStateDeferralApi", true);
		
		config.add("customBlockStateSync", new JsonObject());
		
		config.addProperty("enableIdSyncApi", true);
		
		config.addProperty("forceParallelMode", false);
		
		return config;
	}
}
