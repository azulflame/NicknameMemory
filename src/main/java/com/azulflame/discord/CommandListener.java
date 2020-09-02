package com.azulflame.discord;/*
 * Created by Todd on 8/29/2020.
 */

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.concurrent.Task;

import java.sql.*;
import java.util.*;

public class CommandListener extends ListenerAdapter
{
	private Message pendingFullLoadMessage;
	private ArrayList<String> loadedServers;
	private String PREFIX;
	private Connection conn;
	private String LOGCHANNEL;
	private static Task<List<Member>> memberLoadTask;
	public CommandListener(String DB_URL, String USER, String PASS, String PREFIX, String LOGCHANNEL) throws SQLException
	{
		loadedServers = new ArrayList<String>();
		this.PREFIX = PREFIX;
		createDatabaseConnection(DB_URL, USER, PASS);
		this.LOGCHANNEL = LOGCHANNEL;
	}
	private void createDatabaseConnection(String DB_URL, String USER, String PASS) throws SQLException
	{
		conn = DriverManager.getConnection(DB_URL, USER, PASS);
	}
	@Override
	public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event)
	{
		roleUpdate(event);
	}
	@Override
	public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event)
	{
		roleUpdate(event);
	}
	
	private void roleUpdate(Event e)
	{
		StrippedMember member;
		
		if (e instanceof GuildMemberRoleAddEvent)
		{
			GuildMemberRoleAddEvent event = (GuildMemberRoleAddEvent) e;
			member = new StrippedMember(event.getMember());
		}
		else if (e instanceof GuildMemberRoleRemoveEvent)
		{
			GuildMemberRoleRemoveEvent event = (GuildMemberRoleRemoveEvent) e;
			member = new StrippedMember(event.getMember());
		}
		else
		{
			// should never be called, but the compiler demands it
			member = new StrippedMember();
		}

		String query;
		if(userDataExists(member))
		{
			query = "update users set roles='" + member.getRoleString() + "' where userID = '" + member.getID() + "' and serverID = '" + member.getGuildID() + "'";
		}
		else
		{
			query = "insert into users values ('" + member.getID() + "', '" + member.getRoleString() + "', '" + member.getNickname() + "', '" + member.getGuildID() + "')";
		}
		try
		{
			Statement s = conn.createStatement();
			s.execute(query);
		} catch (SQLException throwables)
		{
			logError(e.getJDA().getGuildById(member.getGuildID()), "Error running query `" + query);
		}
	}
	@Override
	public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		member.setNickname(event.getNewNickname());
		try
		{
			String query;
			if(userDataExists(member))
			{
				query = "update users set nickname='" + member.getNickname() + "' where userID = '" + member.getID() + "' and serverID = '" + member.getGuildID() + "'";
			}
			else
			{
				query = "insert into users values ('" + member.getID() + "', '" + member.getRoleString() + "', '" + member.getNickname() + "', '" + member.getGuildID() + "')";
			}
			Statement s;
			s = conn.createStatement();
			s.execute(query);
		}
		catch(Exception e)
		{
			logError(event.getGuild(), "Error updating nickname for user " + member.getID() + " to " + member.getNickname());
		}
	}
	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		// check if the user has been on the server before'
		// if they have, assign the old roles and nickname
		String query = "select userID, roles, nickname from users where userID = " + member.getID() + " and serverID = " + member.getGuildID();
		Statement s;
		try
		{
			s = conn.createStatement();
			ResultSet rs = s.executeQuery(query);
			if (rs.next()) // if we have a user with the same ID
			{
				// get the last nickname and roles
				String nickname = rs.getString("nickname");
				String roleString = rs.getString("roles");
				member.setRoles(roleString);
				member.setNickname(nickname);
				logEvent(event, member);
				// assign nickname
				if (nickname != null && nickname != "")
				{
					event.getMember().modifyNickname(nickname).queue();
				}
				// assign roles
				if(roleString.length() > 0)
				{
					String[] roleArray = roleString.split(",");
					for (String str: roleArray)
					{
						event.getGuild().addRoleToMember(event.getMember().getId(), event.getGuild().getRolesByName(str, true).get(0)).queue();
					}
				}
			}
		}
		catch(SQLException ex)
		{
			logError(event.getGuild(), "Unable to set role or nickname for user " + member.getID() + ", roles: " + member.getRoleString() + " nick: " + member.getNickname());
		}
	}
	@Override
	public void onGuildMessageReceived(GuildMessageReceivedEvent event)
	{
		if(event.getAuthor().isBot())
		{
			return;
		}
		if((event.getMember().hasPermission(Permission.ADMINISTRATOR) || event.getMember().getId().equals(System.getenv("OWNERID"))) && event.getMessage().getContentRaw().startsWith(PREFIX)) // creator override
		{
			String compare1 = PREFIX + "reloadusers";
			String compare2 = compare1 + " --confirm";
			if(event.getMessage().getContentRaw().startsWith(compare2))
			{
				// reload the entire user cache
				// but only if we haven't done it since the bot launched
				if(loadedServers.contains(event.getGuild().getId()))
				{
					logError(event.getGuild(), "The reload users command was attempted more than once after a bot reload");
					event.getMessage().addReaction("❌").complete();
					
				}
				else if (memberLoadTask == null || !memberLoadTask.isStarted())
				{
					pendingFullLoadMessage = event.getMessage();
					pendingFullLoadMessage.addReaction("⌛").complete();
					memberLoadTask = event.getGuild().loadMembers();
					memberLoadTask.onSuccess(this::storeUsers);
					loadedServers.add(event.getGuild().getId());
				}
				else
				{
					logError(event.getGuild(), "The member reload is currently running");
					event.getMessage().addReaction("❌").complete();
				}
					// red X to show that we aren't going to
			}
			else if(event.getMessage().getContentRaw().startsWith(compare1))
			{
				event.getChannel().sendMessage( "You must run `reloadusers` with `--confirm`").complete();
			}
		}
	}
	
	private void storeUsers(List<Member> members)
	{
		Message temp = pendingFullLoadMessage;
		for(Member m : members)
		{
			StrippedMember member = new StrippedMember(m);
			try
			{
				if(!userDataExists(member))
				{
					Statement s = conn.createStatement();
					String query = "insert into users values ('" + member.getID() + "', '" + member.getRoleString() + "', '" + member.getNickname() + "', '" + member.getGuildID() + "')";
					s.execute(query);
				}
				else
				{
					Statement s = conn.createStatement();
					String query = "update users set roles='" + member.getRoleString() + "', nickname='" + member.getNickname() + "' where userID = '" + member.getID() + "' and serverID = " + member.getGuildID();
					s.execute(query);
				}
			}
			catch (SQLException throwables)
			{
				logError(pendingFullLoadMessage.getGuild(), "Error loading user error for user " + member.getID());
			}
		}
		// finished with the pull
		temp.clearReactions().complete();
		temp.addReaction("✅").complete();
	}
	
	private boolean userDataExists(StrippedMember member)
	{
		String query = "select userID from users where userID = '" + member.getID() + "' and serverID = '" + member.getGuildID() + "'";
		try
		{
			Statement s;
			s = conn.createStatement();
			ResultSet rs = s.executeQuery(query);
			return rs.next();
		}
		catch(Exception e)
		{
			return false;
		}
	}
	
	private void logEvent(Event event, StrippedMember member)
	{
		String toSend = "";
		// sql can be weird, have to check all 3
		if (member.getNickname().equals("") || member.getNickname().equals("NULL"))
		{
			toSend = member.getID() + " joined the server, restoring roles `" + member.getRoleString() + "`";
		}
		else
		{
			toSend = member.getID() + " joined the channel, restoring roles `" + member.getRoleString() + "` and nickname `" + member.getNickname() + "`";
		}
		event.getJDA().getGuildById(member.getGuildID()).getTextChannelsByName(LOGCHANNEL, true).get(0).sendMessage(toSend).complete();
	}
	private void logError(Guild g, String output)
	{
		g.getTextChannelsByName(LOGCHANNEL, true).get(0).sendMessage(output).complete();
	}
}
