package com.azulflame.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import javax.security.auth.login.LoginException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Bot extends ListenerAdapter
{
	static String DB_URL = System.getenv("DBURL");
	static String USER = System.getenv("DBUSER");
	static String PASS = System.getenv("DBPASS");
	static String OAUTH;
	static String LOGCHANNEL;
	static String PREFIX;


	public static void main(String args[]) throws LoginException
	{
		boolean quit = false;
		if(System.getenv("DBURL").equals("") || System.getenv("DBURL") == null)
		{
			System.out.println("$DBURL not found");
			quit = true;
		}
		if(System.getenv("DBUSER").equals("") || System.getenv("DBUSER") == null)
		{
			System.out.println("$DBUSER not found");
			quit = true;
		}
		if(System.getenv("DBPASS").equals("") || System.getenv("DBPASS") == null)
		{
			System.out.println("$DBPASS not found");
			quit = true;
		}
		if(System.getenv("OAUTH").equals("") || System.getenv("OAUTH") == null)
		{
			System.out.println("$OAUTH not found");
			quit = true;
		}
		if(System.getenv("PREFIX").equals("") || System.getenv("PREFIX") == null)
		{
			System.out.println("$PREFIX not found");
			quit = true;
		}
		if(System.getenv("LOGCHANNEL").equals("") || System.getenv("LOGCHANNEL") == null)
		{
			System.out.println("$LOGCHANNEL not found");
			quit = true;
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
