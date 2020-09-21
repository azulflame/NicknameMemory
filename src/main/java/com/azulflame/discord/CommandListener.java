package com.azulflame.discord;/*
 * Created by Todd on 8/29/2020.
 */

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
		roleUpdate(event.getGuild(), member);
	}

	@Override
	public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		roleUpdate(event.getGuild(), member);
	}

	private void roleUpdate(Guild g, StrippedMember member)
	{
		try
		{
			addOrUpdateUser(member);
		}
		catch(SQLException throwables)
		{
			logger.error("Database error while storing roles {} for user {} in guild {}", member.getRoleString(), member.getID(), member.getGuildID());
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwables.printStackTrace(printWriter);
			logger.error("{}", stringWriter.toString());
			logError(g, "Error storing roles for user `" + member.getID() + "`, please contact the bot owner");
		}
	}

	@Override
	public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent event)
	{
		StrippedMember member = new StrippedMember(event.getMember());
		member.setNickname(event.getNewNickname());
		try
		{
			addOrUpdateUser(member);
		}
		catch(SQLException e)
		{
			logError(event.getGuild(), "Error storing nickname for user `" + member.getID() + "`, please contact the bot owner");
			logger.error("Error updating nickname for user {} to {} in {}", member.getID(), member.getNickname(), member.getGuildID());
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			e.printStackTrace(printWriter);
			logger.error("{}", stringWriter.toString());
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
			if(rs.next()) // if we have a user with the same ID
			{
				// get the last nickname and roles
				String nickname = rs.getString("nickname");
				String roleString = rs.getString("roles");
				member.setRoles(roleString);
				member.setNickname(nickname);
				logEvent(event, member);
				// assign nickname
				if(nickname != null && nickname != "")
				{
					try
					{
						event.getMember().modifyNickname(nickname).queue();
						logger.info("Applied nickname {} to user {} on {} due to rejoin", nickname, member.getID(), member.getGuildID());
					}
					catch(InsufficientPermissionException ex)
					{
						logger.info("Failed to apply nickname to ?: Insufficient Permissions");
						logError(event.getGuild(), "Missing permission " + ex.getPermission().toString() + " to assign nicknames to joining users");
					}
				}
				// assign roles
				if(roleString.length() > 0)
				{
					String[] roleArray = roleString.split(",");
					try
					{
						for(String str : roleArray)
						{
							if(event.getGuild().getRoleById(str) == null)
							{
								logger.info("Error assigning role {} to {} on {} - role no longer exists", str, member.getID(), member.getGuildID());
								logError(event.getGuild(), "Could not assign role `" + str + "` to `" + member.getID() + "', as it no longer exists");
							}
							else
							{
								event.getGuild().addRoleToMember(event.getMember().getId(), event.getGuild().getRoleById(str)).queue();
							}
						}
						logger.info("Applied roles {} to user {} on {} due to rejoin", member.getRoleString(), member.getID(), member.getGuildID());
					}
					catch(InsufficientPermissionException ex)
					{
						logger.info("Failed to apply roles to ?: Insufficient Permissions");
						logError(event.getGuild(), "Missing permission " + ex.getPermission().toString() + " to assign roles to joining users");
					}
				}
			}
		}
		catch(SQLException ex)
		{
			logger.error("Unable to update user {} on guild {} to roles {} or nickname {} ", member.getID(), member.getGuildID(), member.getRoleString(), member.getNickname());
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
		String disconnect = PREFIX + "shutdown";
		if(event.getMessage().getContentRaw().startsWith(disconnect) && event.getMember().getId().equals(System.getenv("OWNERID")))
		{
			event.getMessage().addReaction("✅").complete();
			logger.info("Shutting down JDA at owner's request");
			event.getJDA().shutdown();
		}
		else if(event.getMessage().getContentRaw().startsWith(PREFIX) && (event.getMember().hasPermission(Permission.ADMINISTRATOR) || event.getMember().getId().equals(System.getenv("OWNERID")))) // creator override
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
				else if(memberLoadTask == null || !memberLoadTask.isStarted())
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
			else if(event.getMessage().getContentRaw().startsWith(compare1))
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
				addOrUpdateUser(member);
			}
			catch(SQLException throwables)
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

	// log events to the logging channel
	private void logEvent(Event event, StrippedMember member)
	{
		String toSend = "";
		if(member.getNickname().equals("") || member.getNickname().equals("NULL"))
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

	private void addOrUpdateUser(StrippedMember member) throws SQLException
	{
		String query = "INSERT INTO users (userID, nickname, roles, serverID) VALUES ( ? , ? , ? , ?) ON DUPLICATE KEY UPDATE nickname = ?, roles = ?";
		PreparedStatement statement = conn.prepareStatement(query);
		statement.setString(1, member.getID());
		statement.setString(2, member.getNickname());
		statement.setString(3, member.getRoleString());
		statement.setString(4, member.getGuildID());
		statement.setString(5, member.getNickname());
		statement.setString(6, member.getRoleString());
		int rowsChanged = statement.executeUpdate();
		switch(rowsChanged)
		{
			case 0:
				logger.info("Tried updating information for user {} on {}, but it was already updated", member.getID(), member.getGuildID());
				break;
			case 1:
				logger.info("Database information saved for new user {} r: {} n: {} g: {}", member.getID(), member.getRoleString(), member.getNickname(), member.getGuildID());
				break;
			case 2:
				logger.info("Database information updated for user {} r: {} n: {} g: {}", member.getID(), member.getRoleString(), member.getNickname(), member.getGuildID());
				break;
		}
	}
}
