#!/usr/bin/env ruby

require 'uri'
require 'net/http'
require 'json'
require 'pathname'

@api_root = URI("https://api.github.com")

remote = `git config --local remote.origin.url`.chomp
_, login, site, @author, @project = %r{(.+)@(.+):(.+)/(.+)}.match(remote).to_a

@project = @project[0..-5] if /.git\Z/.match(@project)

unless (login=='git' && site=='github.com')
  warn 'Was expecting "git config --local remote.origin.url" to start with something under git@github.com'
  exit 1
end

class ApiError < RuntimeError
  attr_reader :code, :body
  def initialize(msg, code, body)
    @code = code
    @body = body
    super(msg)
  end
end

def http_request(method, url)
  url = URI(url)
  req = Net::HTTP.const_get(method.to_s).new(url).tap {|r|
    r['Authorization']='token ' + ENV['GITHUB_TOKEN'];
    r['user-agent'] = @author
    yield r if block_given?
  }

  response = Net::HTTP.start(url.host, url.port,
                             use_ssl: (url.scheme=='https')) do |h|
    h.request(req)
  end
  code = response.code.to_i
  if (code != 204) && (response.content_type=='application/json')
    body = JSON.parse(response.body)
  else
    body = response.body
  end
  if code >= 400
    raise ApiError.new("#{method} request to #{url} responded code #{code}: #{response.body}", code, body)
  end
  return {code: code, body: body}
end

def make_release(args)
  http_request(:Post, @api_root+"/repos/#{@author}/#{@project}/releases") {|r|
    r.body={target_commitish: 'master',
            body: `git log -1 --pretty=format:%s`}.
            merge(args).to_json
  }[:body]
end

def tags
  http_request(:Get, @api_root+"/repos/#{@author}/#{@project}/tags")[:body]
end

def delete_releases
  tags.each do |tag|
    begin
      release = http_request(:Get, @api_root+("/repos/#{@author}/#{@project}/releases/tags/" + tag['name']))[:body]
      if id = release['id']
        release['assets'].each do |asset|
          warn http_request(:Delete, asset['url'])
        end
        warn http_request(:Delete, @api_root+("/repos/#{@author}/#{@project}/releases/"+id.to_s))
      else
        raise "no id in release for #{tag['name']}?"
      end
    rescue ApiError => e
      if e.code == 404
        warn "found no release for tag #{tag['name']}:  " + e.body.inspect
      else
        raise e
      end
    end
  end
end


# delete_releases
# exit 0

tag = [File.read("VERSION").chomp,
       ENV.fetch("PATCH_LEVEL")].join(".")

release = make_release(tag_name: ('v' + tag),
                       name: tag)
if release["message"]=="Validation Failed"
  release["errors"].each do |e|
    warn "error in field #{e['field'].inspect} : #{e['code'].inspect}"
  end
end

upload_url_template = release["upload_url"]
expr = upload_url_template.match(%r[{(.*?)}])

def upload_file(base_url, path)
  url = base_url + '?name=' + path.basename.to_s
  http_request(:Post, URI(url)) {|r|
    data = File.read(path)
    r.body = data
    r.content_length = data.length
    r.content_type = 'application/octet-stream'
    warn r.content_type
  }
end

ARGV.each do |fn|
  path = Pathname.new(fn)
  print "Uploading #{path.to_s} to #{upload_url_template}"
  puts upload_file(expr.pre_match, path)
end
