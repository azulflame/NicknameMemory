package com.azulflame.discord;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.List;

public class StrippedMember
{
	private String id;
	private String guildID;
	private String roles;
	private String nickname;
	
	public StrippedMember()
	{
		id = "";
		guildID = "";
		roles = "";
		nickname = "";
	}
	
	public StrippedMember(Member member)
	{
		id = member.getId();
		guildID = member.getGuild().getId();
		roles = rolesToString(member.getRoles());
		nickname = "";
		if(member.getNickname() != null)
		{
			nickname = member.getNickname();
		}
	}
	
	private String rolesToString(List<Role> roleList)
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
	
	public String getID() { return id;}
	public String getGuildID() { return guildID;}
	public String getRoleString() { return roles; }
	public String getNickname() { return nickname; }
	// there's no reason to set the ID or guildID
	public void setRoles(String roles) { this.roles = roles;}
	public void setNickname(String nickname) {
		if(nickname == null)
			nickname = "";
		this.nickname = nickname;}
}
