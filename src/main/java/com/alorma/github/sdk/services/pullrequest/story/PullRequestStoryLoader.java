package com.alorma.github.sdk.services.pullrequest.story;

import android.support.annotation.NonNull;
import android.util.Log;

import com.alorma.github.sdk.bean.dto.response.GithubComment;
import com.alorma.github.sdk.bean.dto.response.Label;
import com.alorma.github.sdk.bean.dto.response.PullRequest;
import com.alorma.github.sdk.bean.info.IssueInfo;
import com.alorma.github.sdk.bean.issue.IssueEvent;
import com.alorma.github.sdk.bean.issue.IssueStoryComment;
import com.alorma.github.sdk.bean.issue.IssueStoryComparators;
import com.alorma.github.sdk.bean.issue.IssueStoryDetail;
import com.alorma.github.sdk.bean.issue.IssueStoryEvent;
import com.alorma.github.sdk.bean.issue.PullRequestStory;
import com.alorma.github.sdk.services.client.BaseInfiniteCallback;
import com.alorma.github.sdk.services.client.GithubClient;
import com.alorma.github.sdk.services.issues.story.IssueStoryService;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.Collections;
import java.util.List;

import retrofit.RestAdapter;
import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;

/**
 * Created by Bernat on 07/04/2015.
 */
public class PullRequestStoryLoader extends GithubClient<PullRequestStory> {

  private final IssueInfo issueInfo;
  private final String owner;
  private final String repo;
  private final int num;
  private final IssueStoryService issueStoryService;
  private PullRequestStoryService pullRequestStoryService;

  public PullRequestStoryLoader(IssueInfo info) {
    super();
    this.issueInfo = info;
    this.owner = issueInfo.repoInfo.owner;
    this.repo = issueInfo.repoInfo.name;
    this.num = issueInfo.num;
    pullRequestStoryService = getRestAdapter().create(PullRequestStoryService.class);
    issueStoryService = getRestAdapter().create(IssueStoryService.class);
  }

  @Override
  protected Observable<PullRequestStory> getApiObservable(RestAdapter restAdapter) {
    return getPullrequestStory();
  }

  @NonNull
  private Observable<PullRequestStory> getPullrequestStory() {
    return Observable.zip(getPullRequestObs(), getIssueDetailsObservable(),
        new Func2<PullRequest, List<IssueStoryDetail>, PullRequestStory>() {
          @Override
          public PullRequestStory call(PullRequest pullRequest, List<IssueStoryDetail> details) {
            PullRequestStory pullRequestStory = new PullRequestStory();
            pullRequestStory.pullRequest = pullRequest;
            pullRequestStory.details = details;
            Collections.sort(pullRequestStory.details, IssueStoryComparators.ISSUE_STORY_DETAIL_COMPARATOR);
            return pullRequestStory;
          }
        });
  }

  private Observable<PullRequest> getPullRequestObs() {
    Observable<PullRequest> pullRequestObservable = pullRequestStoryService.detailObs(owner, repo, num).subscribeOn(Schedulers.io());

    return Observable.zip(pullRequestObservable, getLabelsObs(), new Func2<PullRequest, List<Label>, PullRequest>() {
          @Override
          public PullRequest call(PullRequest pullRequest, List<Label> labels) {
            pullRequest.labels = labels;
            return pullRequest;
          }
        });
  }

  private Observable<List<IssueStoryDetail>> getIssueDetailsObservable() {
    Observable<IssueStoryDetail> commentsDetailsObs = getCommentsDetailsObs();
    Observable<IssueStoryDetail> eventDetailsObs = getEventDetailsObs();
    return Observable.mergeDelayError(commentsDetailsObs, eventDetailsObs).toList();
  }

  @NonNull
  private Observable<List<GithubComment>> getCommentsObs() {
    return Observable.create(new BaseInfiniteCallback<List<GithubComment>>() {
      @Override
      public void execute() {
        issueStoryService.comments(issueInfo.repoInfo.owner, issueInfo.repoInfo.name, issueInfo.num, this);
      }

      @Override
      protected void executePaginated(int nextPage) {
        issueStoryService.comments(issueInfo.repoInfo.owner, issueInfo.repoInfo.name, issueInfo.num, nextPage, this);
      }
    });
  }

  private Observable<IssueStoryDetail> getCommentsDetailsObs() {
    return getCommentsObs().subscribeOn(Schedulers.io()).flatMap(new Func1<List<GithubComment>, Observable<IssueStoryDetail>>() {
      @Override
      public Observable<IssueStoryDetail> call(List<GithubComment> githubComments) {
        return Observable.from(githubComments).map(new Func1<GithubComment, IssueStoryDetail>() {
          @Override
          public IssueStoryDetail call(GithubComment githubComment) {
            long time = getMilisFromDateClearDay(githubComment.created_at);
            IssueStoryComment detail = new IssueStoryComment(githubComment);
            detail.created_at = time;
            return detail;
          }
        });
      }
    });
  }

  @NonNull
  private Observable<List<IssueEvent>> getEventsObs() {
    return Observable.create(new BaseInfiniteCallback<List<IssueEvent>>() {

      @Override
      public void execute() {
        issueStoryService.events(issueInfo.repoInfo.owner, issueInfo.repoInfo.name, issueInfo.num, this);
      }

      @Override
      protected void executePaginated(int nextPage) {
        issueStoryService.events(issueInfo.repoInfo.owner, issueInfo.repoInfo.name, issueInfo.num, nextPage, this);
      }
    });
  }

  @NonNull
  private Observable<IssueStoryDetail> getEventDetailsObs() {
    return getEventsObs().subscribeOn(Schedulers.io()).flatMap(new Func1<List<IssueEvent>, Observable<IssueStoryDetail>>() {
      @Override
      public Observable<IssueStoryDetail> call(List<IssueEvent> issueEvents) {
        return Observable.from(issueEvents).filter(new Func1<IssueEvent, Boolean>() {
          @Override
          public Boolean call(IssueEvent issueEvent) {
            return validEvent(issueEvent.event);
          }
        }).map(new Func1<IssueEvent, IssueStoryDetail>() {
          @Override
          public IssueStoryDetail call(IssueEvent issueEvent) {
            long time = getMilisFromDateClearDay(issueEvent.created_at);
            IssueStoryEvent detail = new IssueStoryEvent(issueEvent);
            detail.created_at = time;
            return detail;
          }
        });
      }
    });
  }

  @NonNull
  private Observable<List<Label>> getLabelsObs() {
    return Observable.create(new BaseInfiniteCallback<List<Label>>() {
      @Override
      public void execute() {
        issueStoryService.labels(owner, repo, num, this);
      }

      @Override
      protected void executePaginated(int nextPage) {
        issueStoryService.labels(owner, repo, num, nextPage, this);
      }
    });
  }

  private long getMilisFromDateClearDay(String createdAt) {
    DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    DateTime dt = formatter.parseDateTime(createdAt);

    return dt.minuteOfDay().roundFloorCopy().getMillis();
  }

  private boolean validEvent(String event) {
    return !(event.equals("mentioned") ||
        event.equals("subscribed") ||
        event.equals("unsubscribed") ||
        event.equals("labeled") ||
        event.equals("unlabeled"));
  }

  @Override
  public void log(String message) {
    Log.i("IssueStoryLoader", message);
  }

  @Override
  public String getAcceptHeader() {
    return "application/vnd.github.v3.full+json";
  }
}
/*
    private class LabelsCallback extends BaseInfiniteCallback<List<Label>> {

        private final IssueInfo info;
        private final IssueStoryService pullRequestStoryService;
        private final PullRequestsService pullRequestsService;

        public LabelsCallback(IssueInfo info, IssueStoryService pullRequestStoryService, PullRequestsService pullRequestsService) {
            this.info = info;
            this.pullRequestStoryService = pullRequestStoryService;
            this.pullRequestsService = pullRequestsService;
        }


        @Override
        public void execute() {
            pullRequestStoryService.labels(info.repoInfo.owner, info.repoInfo.name, info.num, this);
        }

        @Override
        protected void executePaginated(int nextPage) {
            pullRequestStoryService.labels(info.repoInfo.owner, info.repoInfo.name, info.num, nextPage, this);
        }

        @Override
        protected void executeNext() {
            new IssueCommentsCallback(issueInfo, pullRequestStoryService, pullRequestsService).execute();
        }

        @Override
        protected void response(List<Label> issueLabels) {
            pullRequestStory.pullRequest.labels.addAll(issueLabels);
        }
    }
}
*/