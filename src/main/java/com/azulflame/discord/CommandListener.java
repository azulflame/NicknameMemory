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
		String roleString = "";
		String userID = "";
		String nickname = "";
		String serverID = "";
		
		if (e instanceof GuildMemberRoleAddEvent)
		{
			GuildMemberRoleAddEvent event = (GuildMemberRoleAddEvent) e;
			roleString = stringify(event.getMember().getRoles());
			userID = event.getMember().getId();
			serverID = event.getGuild().getId();
			if(event.getMember().getNickname() != null)
			{
				nickname = event.getMember().getNickname();
			}
		}
		if (e instanceof GuildMemberRoleRemoveEvent)
		{
			GuildMemberRoleRemoveEvent event = (GuildMemberRoleRemoveEvent) e;
			roleString = stringify(event.getMember().getRoles());
			userID = event.getMember().getId();
			serverID = event.getGuild().getId();
			if(event.getMember().getNickname() != null)
			{
				nickname = event.getMember().getNickname();
			}
		}

		String query;
		if(userDataExists(userID, serverID))
		{
			query = "update users set roles='" + roleString + "' where userID = '" + userID + "' and serverID = '" + serverID + "'";
		}
		else
		{
			query = "insert into users values ('" + userID + "', '" + roleString + "', '" + nickname + "', '" + serverID + "')";
		}
		try
		{
			Statement s = conn.createStatement();
			s.execute(query);
		} catch (SQLException throwables)
		{
			throwables.printStackTrace();
		}
	}
	@Override
	public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent event)
	{
		String newNick = event.getNewNickname();
		String userID = event.getMember().getId();
		String serverID = event.getGuild().getId();
		try
		{
			String query;
			if(userDataExists(userID, serverID))
			{
				query = "update users set nickname='" + newNick + "' where userID = '" + userID + "' and serverID = '" + serverID + "'";
			}
			else
			{
				query = "insert into users values ('" + userID + "', '" + stringify(event.getMember().getRoles()) + "', '" + newNick + "', '" + serverID + "')";
			}
			Statement s;
			s = conn.createStatement();
			s.execute(query);
		}
		catch(Exception e)
		{
			return;
		}
	}
	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event)
	{
		// check if the user has been on the server before'
		// if they have, assign the old roles and nickname
		String query = "select userID, roles, nickname from users where userID = " + event.getMember().getId() + " and serverID = " + event.getGuild().getId();
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
				logEvent(event, roleString, nickname);
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
			// shouldn't arrive here ideally
			// so we'll just silently fail
			return;
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
				if (!loadedServers.contains(event.getGuild().getId()))
				{
					if (memberLoadTask == null || !memberLoadTask.isStarted())
					{
						pendingFullLoadMessage = event.getMessage();
						pendingFullLoadMessage.addReaction("⌛").complete();
						memberLoadTask = event.getGuild().loadMembers();
						memberLoadTask.onSuccess(this::storeUsers);
						loadedServers.add(event.getGuild().getId());
					}
				}
				else
				{
					// red X to show that we aren't going to
					event.getMessage().addReaction("❌");
				}
			}
			else if(event.getMessage().getContentRaw().startsWith(compare1))
			{
				event.getChannel().sendMessage( "You must run `reloadusers` with `--confirm`").complete();
				return;
			}
		}
	}
	
	private void storeUsers(List<Member> members)
	{
		Message temp = pendingFullLoadMessage;
		for(Member m : members)
		{
			try
			{
				if(!userDataExists(m.getId(), m.getGuild().getId()))
				{
					Statement s = conn.createStatement();
					String query = "insert into users values ('" + m.getId() + "', '" + stringify(m.getRoles()) + "', '" + m.getNickname() + "', '" + m.getGuild().getId() + "')";
					s.execute(query);
				}
				else
				{
					Statement s = conn.createStatement();
					String query = "update users set roles='" + stringify(m.getRoles()) + "', nickname='" + m.getNickname() + "' where userID = '" + m.getId() + "' and serverID = " + m.getGuild().getId();
					s.execute(query);
				}
			}
			catch (SQLException throwables)
			{
				throwables.printStackTrace();
			}
		}
		// finished with the pull
		temp.clearReactions().complete();
		temp.addReaction("✅").complete();
	}
	
	private boolean userDataExists(String userID, String serverID)
	{
		String query = "select userID from users where userID = '" + userID + "' and serverID = '" + serverID + "'";
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
	private String stringify(List<Role> roleList)
	{
		String out = "";
		for(int i = 0; i < roleList.size(); i++)
		{
			out += roleList.get(i).getName() + ",";
		}
		if(out.length() > 0)
		{
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}
	private void logEvent(GuildMemberJoinEvent event, String roles, String nickname)
	{
		String toSend = "";
		// sql can be weird, have to check all 3
		if (nickname == null || nickname.equals("") || nickname.equals("NULL"))
		{
			toSend = event.getMember().getId() + " joined the server, restoring roles `" + roles + "`";
		}
		else
		{
			toSend = event.getMember().getId() + " joined the channel, restoring roles `" + roles + "` and nickname `" + nickname + "`";
		}
		event.getGuild().getTextChannelsByName(LOGCHANNEL, true).get(0).sendMessage(toSend).complete();
	}
}
