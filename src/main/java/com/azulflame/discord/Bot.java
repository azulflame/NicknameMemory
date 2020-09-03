package com.azulflame.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import javax.security.auth.login.LoginException;
import java.sql.SQLException;

public class Bot extends ListenerAdapter
{
	static String DB_URL;
	static String USER;
	static String PASS;
	static String OAUTH;
	static String LOGCHANNEL;
	static String PREFIX;


	public static void main(String args[]) throws LoginException
	{
		String[] requiredEnvironmentVariables = {"DBURL", "DBUSER", "DBPASS","OAUTH", "PREFIX", "LOGCHANNEL" };
		boolean quit = false;
		for(String s : requiredEnvironmentVariables)
		{
			
			if (System.getenv(s) == null || System.getenv(s).equals(""))
			{
				System.out.println("$" + s + " not found");
				quit = true;
			}
		}
		if(quit)
		{
			return;
		}
		DB_URL = System.getenv("DBURL");
		USER = System.getenv("DBUSER");
		PASS = System.getenv("DBPASS");
		OAUTH = System.getenv("OAUTH");
		PREFIX = System.getenv("PREFIX");
		LOGCHANNEL = System.getenv("LOGCHANNEL");
		try
		{
			JDA bot = JDABuilder.create(OAUTH, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS)
					.disableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE, CacheFlag.EMOTE, CacheFlag.CLIENT_STATUS)
					.setMemberCachePolicy(MemberCachePolicy.ALL)
					.addEventListeners(new CommandListener(DB_URL, USER, PASS, PREFIX, LOGCHANNEL))
					.build();
			bot.awaitReady();
		}
		catch(SQLException e)
		{
			e.printStackTrace();
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
	}


}
