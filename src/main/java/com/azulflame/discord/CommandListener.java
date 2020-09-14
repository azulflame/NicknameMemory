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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
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
	Logger logger;
	
	public CommandListener(String DB_URL, String USER, String PASS, String PREFIX, String LOGCHANNEL) throws SQLException
	{
		logger = LoggerFactory.getLogger(this.getClass());
		loadedServers = new ArrayList<>();
		this.PREFIX = PREFIX;
		createDatabaseConnection(DB_URL, USER, PASS);
		this.LOGCHANNEL = LOGCHANNEL;
	}
	
	@Override
	public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		roleUpdate(event, member);
	}
	
	@Override
	public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		roleUpdate(event, member);
	}
	
	private void roleUpdate(Event e, StrippedMember member)
	{
		try
		{
			updateRoles(member);
			logger.info("Updated roles {} for user {} on guild {}", member.getRoleString(), member.getID(), member.getGuildID());
		} catch (SQLException throwables)
		{
			logger.error("Error updating roles to {} for user {} in guild {}", member.getRoleString(), member.getID(), member.getGuildID());
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwables.printStackTrace(printWriter);
			logger.error("{}", stringWriter.toString());
			logError(e.getJDA().getGuildById(member.getGuildID()), "Error updating roles for user `" + member.getID() + "`");
		}
	}
	
	@Override
	public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		member.setNickname(event.getNewNickname());
		try
		{
			updateNickname(member);
			logger.info("Stored nickname {} for user {} on guild {}", member.getNickname(), member.getID(), member.getGuildID());
		} catch (Exception e)
		{
			logger.error("Error updating nickname for user {} to {} in {}", member.getID(), member.getNickname(), member.getGuildID());
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			e.printStackTrace(printWriter);
			logger.error("{}", stringWriter.toString());
			logError(event.getGuild(), "Error updating nickname for user " + member.getID() + " to " + member.getNickname());
		}
	}
	
	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		// check if the user has been on the server before
		// if they have, assign the old roles and nickname
		String query = "select userID, roles, nickname from users where userID = ? and serverID = ?";
		PreparedStatement statement;
		try
		{
			statement = conn.prepareStatement(query);
			statement.setString(1, member.getID());
			statement.setString(2, member.getGuildID());
			ResultSet rs = statement.executeQuery();
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
					logger.info("Applied nickname {} to user {} on {}", nickname, member.getID(), member.getGuildID());
				}
				// assign roles
				if (roleString.length() > 0)
				{
					String[] roleArray = roleString.split(",");
					for (String str : roleArray)
					{
						event.getGuild().addRoleToMember(event.getMember().getId(), event.getGuild().getRolesByName(str, true).get(0)).queue();
					}
					logger.info("Applied roles {} to user {} on {}", member.getRoleString(), member.getID(), member.getGuildID());
				}
			}
		} catch (SQLException ex)
		{
			logger.error("Unable to update user {} on guild {} to roles {} or nickname {} ", member.getID(), member.getGuildID(), member.getRoleString(), member.getNickname());
			logError(event.getGuild(), "Unable to set role or nickname for user " + member.getID() + ", roles: " + member.getRoleString() + " nick: " + member.getNickname());
		}
	}
	
	@Override
	public void onGuildMessageReceived(GuildMessageReceivedEvent event)
	{
		if (event.getAuthor().isBot())
		{
			return;
		}
		if ((event.getMember().hasPermission(Permission.ADMINISTRATOR) || event.getMember().getId().equals(System.getenv("OWNERID"))) && event.getMessage().getContentRaw().startsWith(PREFIX)) // creator override
		{
			String compare1 = PREFIX + "reloadusers";
			String compare2 = compare1 + " --confirm";
			if (event.getMessage().getContentRaw().startsWith(compare2))
			{
				// reload the entire user cache
				// but only if we haven't done it since the bot launched
				if (loadedServers.contains(event.getGuild().getId()))
				{
					logError(event.getGuild(), "The reload users command was attempted more than once after a bot reload");
					event.getMessage().addReaction("❌").complete();
					
				}
				else
					if (memberLoadTask == null || !memberLoadTask.isStarted())
					{
						pendingFullLoadMessage = event.getMessage();
						pendingFullLoadMessage.addReaction("⌛").complete();
						logger.info("Started pulling members for guild {}", event.getGuild().getId());
						memberLoadTask = event.getGuild().loadMembers();
						memberLoadTask.onSuccess(this::storeUsers);
						memberLoadTask.onError(this::storeUsersError);
						loadedServers.add(event.getGuild().getId());
					}
					else
					{
						logError(event.getGuild(), "The member reload is currently running");
						event.getMessage().addReaction("❌").complete();
					}
				// red X to show that we aren't going to
			}
			else
				if (event.getMessage().getContentRaw().startsWith(compare1))
				{
					event.getChannel().sendMessage("You must run `reloadusers` with `--confirm`").complete();
				}
		}
	}
	
	private void storeUsersError(Throwable throwable)
	{
		logger.error("Failed trying to pull users on guild {}", pendingFullLoadMessage.getGuild().getId());
		StringWriter stringWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(stringWriter);
		throwable.printStackTrace(printWriter);
		logger.error("{}", stringWriter.toString());
		logError(pendingFullLoadMessage.getGuild(), "Error pulling all users");
		loadedServers.remove(pendingFullLoadMessage.getGuild().getId());
		pendingFullLoadMessage = null;
	}
	
	private void createDatabaseConnection(String DB_URL, String USER, String PASS) throws SQLException
	{
		conn = DriverManager.getConnection(DB_URL, USER, PASS);
	}
	private void storeUsers(List<Member> members)
	{
		logger.info("Success pulling users on guild {}", pendingFullLoadMessage.getGuild().getId());
		Message temp = pendingFullLoadMessage;
		for(Member m : members)
		{
			StrippedMember member = new StrippedMember(m);
			try
			{
				updateNickname(member);
				updateRoles(member);
			}
			catch (SQLException throwables)
			{
				logError(pendingFullLoadMessage.getGuild(), "Error loading user info for user " + member.getID());
			}
		}
		// finished with the pull
		temp.clearReactions().complete();
		temp.addReaction("✅").complete();
		logger.info("All users stored into database for guild {}", temp.getGuild().getId());
		pendingFullLoadMessage = null;
	}
	
	private boolean userDataExists(StrippedMember member)
	{
		String query = "select userID from users where userID = ? and serverID = ?";
		try
		{
			PreparedStatement statement= conn.prepareStatement(query);
			statement.setString(1, member.getID());
			statement.setString(2, member.getGuildID());
			ResultSet rs = statement.executeQuery();
			if(rs.wasNull())
			{
				return false;
			}
			return rs.next();
		}
		catch(Exception e)
		{
			logger.error("Error checking user data for user {} on guild {}", member.getID(), member.getGuildID());
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			e.printStackTrace(printWriter);
			logger.error("{}", stringWriter.toString());
			return false;
		}
	}
	// log events to the logging channel
	private void logEvent(Event event, StrippedMember member)
	{
		String toSend = "";
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

	private void updateNickname(StrippedMember member) throws SQLException
	{
		PreparedStatement statement;
		String query;
		if (userDataExists(member))
		{
			query = "update users set nickname = ? where userID = ? and serverID = ?";
			statement = conn.prepareStatement(query);
			statement.setString(1, member.getNickname());
			statement.setString(2, member.getID());
			statement.setString(3, member.getGuildID());
			statement.executeUpdate();
		}
		else
		{
			createUser(member);
		}
	}
	private void updateRoles(StrippedMember member) throws SQLException
	{
		String query;
		PreparedStatement statement;
		if(userDataExists(member))
		{
			query = "update users set roles = ? where userID = ? and serverID = ?";
			statement = conn.prepareStatement(query);
			statement.setString(1,member.getRoleString());
			statement.setString(2, member.getID());
			statement.setString(3, member.getGuildID());
			statement.executeUpdate();
		}
		else
		{
			createUser(member);
		}
	}
	private void createUser(StrippedMember member) throws SQLException
	{
		String query = "insert into users (userID, roles, nickname, serverID) values ( ? , ? , ? , ?)";
		PreparedStatement statement = conn.prepareStatement(query);
		statement.setString(1, member.getID());
		statement.setString(2, member.getNickname());
		statement.setString(3, member.getRoleString());
		statement.setString(4, member.getGuildID());
		statement.executeUpdate();
	}
}
